import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * Minimal command client for the cvte.at_sudo Binder service present in the
 * tested DEXP/YAOS firmware.
 *
 * This does not add root to the firmware. It only talks to a root service that
 * is already shipped by the TV vendor.
 */
public final class AtSudoClient {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: AtSudoClient command");
            System.exit(2);
        }

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "cvte.at_sudo");
        if (binder == null) {
            throw new IllegalStateException("cvte.at_sudo is unavailable");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeString(String.join(" ", args));
            if (!binder.transact(2, data, reply, 0)) {
                throw new IllegalStateException("Binder transaction failed");
            }
            int status = reply.readInt();
            String output = reply.readString();
            if (status != 0) {
                throw new IllegalStateException("Command failed with status " + status);
            }
            if (output != null) {
                System.out.print(output);
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
