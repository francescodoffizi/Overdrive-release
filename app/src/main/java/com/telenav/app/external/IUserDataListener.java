package com.telenav.app.external;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.telenav.app.external.model.userservice.UserDataResult;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public interface IUserDataListener extends IInterface {

    public static class Default implements IUserDataListener {
        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }

        @Override // com.telenav.app.external.IUserDataListener
        public void onFavoriteChanged(String str, UserDataResult userDataResult) throws RemoteException {
        }
    }

    public static abstract class Stub extends Binder implements IUserDataListener {
        private static final String DESCRIPTOR = "com.telenav.app.external.IUserDataListener";
        public static final int TRANSACTION_onFavoriteChanged = 1;

        public static class Proxy implements IUserDataListener {
            public static IUserDataListener sDefaultImpl;
            private IBinder mRemote;

            public Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            @Override // com.telenav.app.external.IUserDataListener
            public void onFavoriteChanged(String str, UserDataResult userDataResult) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    if (userDataResult != null) {
                        parcelObtain.writeInt(1);
                        userDataResult.writeToParcel(parcelObtain, 0);
                    } else {
                        parcelObtain.writeInt(0);
                    }
                    if (this.mRemote.transact(1, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().onFavoriteChanged(str, userDataResult);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IUserDataListener asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            return (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof IUserDataListener)) ? new Proxy(iBinder) : (IUserDataListener) iInterfaceQueryLocalInterface;
        }

        public static IUserDataListener getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }

        public static boolean setDefaultImpl(IUserDataListener iUserDataListener) {
            if (Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            }
            if (iUserDataListener == null) {
                return false;
            }
            Proxy.sDefaultImpl = iUserDataListener;
            return true;
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i != 1) {
                if (i != 1598968902) {
                    return super.onTransact(i, parcel, parcel2, i2);
                }
                parcel2.writeString(DESCRIPTOR);
                return true;
            }
            parcel.enforceInterface(DESCRIPTOR);
            onFavoriteChanged(parcel.readString(), parcel.readInt() != 0 ? UserDataResult.CREATOR.createFromParcel(parcel) : null);
            parcel2.writeNoException();
            return true;
        }
    }

    void onFavoriteChanged(String str, UserDataResult userDataResult) throws RemoteException;
}
