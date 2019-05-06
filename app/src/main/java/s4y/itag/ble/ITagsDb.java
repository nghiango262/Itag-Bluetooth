package s4y.itag.ble;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import s4y.itag.BuildConfig;
import s4y.itag.ITagApplication;

public class ITagsDb {
    private static final String DB_FILE_NAME="dbv1";
    private static final String DB_OLD_FILE_NAME="dboldv1";
    public interface DbListener {
        void onDbChange();
        void onDbAdd(ITagDevice device);
        void onDbRemove(ITagDevice device);
    }

    private final static List<DbListener> mDbListeners = new ArrayList<>(4);
    private static List<ITagDevice> devices;

    public static void addListener(DbListener listener) {
        if (BuildConfig.DEBUG) {
            if (mDbListeners.contains(listener)) {
                ITagApplication.handleError(new Exception("Db.addListener duplcate"));
            }
        }
        mDbListeners.add(listener);
    }

    public static void removeListener(DbListener listener) {
        if (BuildConfig.DEBUG) {
            if (!mDbListeners.contains(listener)) {
                ITagApplication.handleError(new Exception("Db.removeListener non existing"));
            }
        }
        mDbListeners.remove(listener);
    }

    public static void notifyChange() {
        for (DbListener listener : mDbListeners) {
            listener.onDbChange();
        }
    }

    private static void notifyAdd(ITagDevice device) {
        for (DbListener listener : mDbListeners) {
            listener.onDbAdd(device);
        }
    }

    private static void notifyRemove(ITagDevice device) {
        for (DbListener listener : mDbListeners) {
            listener.onDbRemove(device);
        }
    }

    static ITagDevice findByAddr(@NonNull final String addr) {
        for (ITagDevice d : devices) {
            if (d.addr.equals(addr)) return d;
        }
        return null;
    }

    @NonNull
    static private File getDbFile(
            @NonNull final Context context,
            @NonNull final String name) throws IOException {
        File file = new File(context.getFilesDir(), name);
        //noinspection ResultOfMethodCallIgnored
        file.createNewFile();
        return file;
    }

    static private void loadFromFile(
            @NonNull final Context context,
            @NonNull final String fileName,
            @NonNull final List<ITagDevice> devices) {
        devices.clear();

        File file;

        try {
            file = getDbFile(context, fileName);
        } catch (IOException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
            return;
        }

        long l=file.length();
        if (l==0)
            return;

        try (FileInputStream fis = new FileInputStream(file)) {
            ObjectInputStream ois = new ObjectInputStream(fis);
            Object read = ois.readObject();
            if (read instanceof List) {
                List dd = (List) read;
                for (Object d : dd) {
                    if (d instanceof ITagDevice) {
                        devices.add((ITagDevice) d);
                    }
                }
            }
            ois.close();
        } catch (FileNotFoundException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        } catch (IOException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        }
    }

    static private void load(@NonNull final Context context) {
        loadFromFile(context, DB_FILE_NAME, devices);
    }

    @NonNull
    private static List<ITagDevice> loadOldDevices(@NonNull final Context context) {
        final List<ITagDevice> devices = new ArrayList<>(16);
        loadFromFile(context, DB_OLD_FILE_NAME, devices);
        return devices;
    }

    private static ITagDevice getOldDevice(@NonNull final Context context, String addr) {
        final List<ITagDevice> oldDevices = loadOldDevices(context);
        for (ITagDevice oldDevice : oldDevices) {
            if (oldDevice.addr.equals(addr)) {
                return oldDevice;
            }
        }
        return null;
    }

    private static void updateOld(@NonNull final Context context) {
        final List<ITagDevice> composeDevices = new ArrayList<>(16);

        try {
            final List<ITagDevice> oldDevices = loadOldDevices(context);
            for (ITagDevice oldDevice : oldDevices) {
                ITagDevice found = null;
                for (ITagDevice current : devices) {
                    if (oldDevice.addr.equals(current.addr)) {
                        found = current;
                        break;
                    }
                }
                if (found == null) {
                    composeDevices.add(oldDevice);
                } else {
                    composeDevices.add(found);
                }
            }
            for (ITagDevice current : devices) {
                boolean found = false;
                for (ITagDevice oldDevice : oldDevices) {
                    if (oldDevice.addr.equals(current.addr)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    composeDevices.add(current);
                }
            }
        } catch (NullPointerException ignored) {
            try {
                //noinspection ResultOfMethodCallIgnored
                getDbFile(context, DB_OLD_FILE_NAME).delete();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(getDbFile(context, DB_OLD_FILE_NAME))) {
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(composeDevices  );
            oos.close();
        } catch (FileNotFoundException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        } catch (IOException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        }
    }

    public static void save(@NonNull final Context context) {
        try (FileOutputStream fos = new FileOutputStream(getDbFile(context, DB_FILE_NAME))) {
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(devices);
            oos.close();
            updateOld(context);
        } catch (FileNotFoundException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        } catch (IOException e) {
            ITagApplication.handleError(e, true);
            e.printStackTrace();
        }
    }

    static public void remember(@NonNull final Context context, @NonNull final BluetoothDevice device) {
        if (findByAddr(device.getAddress()) == null) {
            final ITagDevice oldDevice = getOldDevice(context, device.getAddress());
            final ITagDevice d = new ITagDevice(device, oldDevice);
            devices.add(d);
            save(context);
            notifyAdd(d);
            notifyChange();
            ITagApplication.faRememberITag();
        }
    }

    static public void forget(@NonNull final Context context, @NonNull final ITagDevice device) {
        ITagDevice existing = findByAddr(device.addr);
        if (existing != null) {
            devices.remove(existing);
            save(context);
            notifyRemove(device);
            notifyChange();
            ITagApplication.faForgetITag();
        }
    }

    static public boolean has(@NonNull final BluetoothDevice device) {
        return findByAddr(device.getAddress()) != null;
    }

    static public boolean has(@NonNull final ITagDevice device) {
        return findByAddr(device.addr) != null;
    }

    static public List<ITagDevice> getDevices(@NonNull Context context){
        if (devices==null){
            devices = new ArrayList<>(4);
            load(context);
        }
        return devices;
    }
}
