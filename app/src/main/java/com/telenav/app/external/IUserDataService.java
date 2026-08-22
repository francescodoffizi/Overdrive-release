package com.telenav.app.external;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.telenav.app.external.model.search.Place;
import com.telenav.app.external.model.userservice.UserDataResult;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public interface IUserDataService extends IInterface {

    public static class Default implements IUserDataService {
        @Override // com.telenav.app.external.IUserDataService
        public void addFavorite(String str, Place place) throws RemoteException {
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }

        @Override // com.telenav.app.external.IUserDataService
        public void clearFavorites() throws RemoteException {
        }

        @Override // com.telenav.app.external.IUserDataService
        public void clearRecent() throws RemoteException {
        }

        @Override // com.telenav.app.external.IUserDataService
        public UserDataResult getFavorites(String str) throws RemoteException {
            return null;
        }

        @Override // com.telenav.app.external.IUserDataService
        public UserDataResult getRecent() throws RemoteException {
            return null;
        }

        @Override // com.telenav.app.external.IUserDataService
        public void registerUserDataListener(IUserDataListener iUserDataListener) throws RemoteException {
        }

        @Override // com.telenav.app.external.IUserDataService
        public void removeFavorite(String str, Place place) throws RemoteException {
        }

        @Override // com.telenav.app.external.IUserDataService
        public void unRegisterUserDataListener(IUserDataListener iUserDataListener) throws RemoteException {
        }
    }

    public static abstract class Stub extends Binder implements IUserDataService {
        private static final String DESCRIPTOR = "com.telenav.app.external.IUserDataService";
        public static final int TRANSACTION_addFavorite = 2;
        public static final int TRANSACTION_clearFavorites = 4;
        public static final int TRANSACTION_clearRecent = 6;
        public static final int TRANSACTION_getFavorites = 1;
        public static final int TRANSACTION_getRecent = 5;
        public static final int TRANSACTION_registerUserDataListener = 7;
        public static final int TRANSACTION_removeFavorite = 3;
        public static final int TRANSACTION_unRegisterUserDataListener = 8;

        public static class Proxy implements IUserDataService {
            public static IUserDataService sDefaultImpl;
            private IBinder mRemote;

            public Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // com.telenav.app.external.IUserDataService
            public void addFavorite(String str, Place place) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    if (place != null) {
                        parcelObtain.writeInt(1);
                        place.writeToParcel(parcelObtain, 0);
                    } else {
                        parcelObtain.writeInt(0);
                    }
                    if (this.mRemote.transact(2, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().addFavorite(str, place);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            @Override // com.telenav.app.external.IUserDataService
            public void clearFavorites() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (this.mRemote.transact(4, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().clearFavorites();
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IUserDataService
            public void clearRecent() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (this.mRemote.transact(6, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().clearRecent();
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IUserDataService
            public UserDataResult getFavorites(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    if (!this.mRemote.transact(1, parcelObtain, parcelObtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getFavorites(str);
                    }
                    parcelObtain2.readException();
                    return parcelObtain2.readInt() != 0 ? UserDataResult.CREATOR.createFromParcel(parcelObtain2) : null;
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            @Override // com.telenav.app.external.IUserDataService
            public UserDataResult getRecent() throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(5, parcelObtain, parcelObtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getRecent();
                    }
                    parcelObtain2.readException();
                    return parcelObtain2.readInt() != 0 ? UserDataResult.CREATOR.createFromParcel(parcelObtain2) : null;
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IUserDataService
            public void registerUserDataListener(IUserDataListener iUserDataListener) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iUserDataListener != null ? iUserDataListener.asBinder() : null);
                    if (this.mRemote.transact(7, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerUserDataListener(iUserDataListener);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IUserDataService
            public void removeFavorite(String str, Place place) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    if (place != null) {
                        parcelObtain.writeInt(1);
                        place.writeToParcel(parcelObtain, 0);
                    } else {
                        parcelObtain.writeInt(0);
                    }
                    if (this.mRemote.transact(3, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().removeFavorite(str, place);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IUserDataService
            public void unRegisterUserDataListener(IUserDataListener iUserDataListener) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iUserDataListener != null ? iUserDataListener.asBinder() : null);
                    if (this.mRemote.transact(8, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unRegisterUserDataListener(iUserDataListener);
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

        public static IUserDataService asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            return (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof IUserDataService)) ? new Proxy(iBinder) : (IUserDataService) iInterfaceQueryLocalInterface;
        }

        public static IUserDataService getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }

        public static boolean setDefaultImpl(IUserDataService iUserDataService) {
            if (Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            }
            if (iUserDataService == null) {
                return false;
            }
            Proxy.sDefaultImpl = iUserDataService;
            return true;
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i == 1598968902) {
                parcel2.writeString(DESCRIPTOR);
                return true;
            }
            switch (i) {
                case 1:
                    parcel.enforceInterface(DESCRIPTOR);
                    UserDataResult favorites = getFavorites(parcel.readString());
                    parcel2.writeNoException();
                    if (favorites != null) {
                        parcel2.writeInt(1);
                        favorites.writeToParcel(parcel2, 1);
                    } else {
                        parcel2.writeInt(0);
                    }
                    return true;
                case 2:
                    parcel.enforceInterface(DESCRIPTOR);
                    addFavorite(parcel.readString(), parcel.readInt() != 0 ? Place.CREATOR.createFromParcel(parcel) : null);
                    parcel2.writeNoException();
                    return true;
                case 3:
                    parcel.enforceInterface(DESCRIPTOR);
                    removeFavorite(parcel.readString(), parcel.readInt() != 0 ? Place.CREATOR.createFromParcel(parcel) : null);
                    parcel2.writeNoException();
                    return true;
                case 4:
                    parcel.enforceInterface(DESCRIPTOR);
                    clearFavorites();
                    parcel2.writeNoException();
                    return true;
                case 5:
                    parcel.enforceInterface(DESCRIPTOR);
                    UserDataResult recent = getRecent();
                    parcel2.writeNoException();
                    if (recent != null) {
                        parcel2.writeInt(1);
                        recent.writeToParcel(parcel2, 1);
                    } else {
                        parcel2.writeInt(0);
                    }
                    return true;
                case 6:
                    parcel.enforceInterface(DESCRIPTOR);
                    clearRecent();
                    parcel2.writeNoException();
                    return true;
                case 7:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerUserDataListener(IUserDataListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 8:
                    parcel.enforceInterface(DESCRIPTOR);
                    unRegisterUserDataListener(IUserDataListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                default:
                    return super.onTransact(i, parcel, parcel2, i2);
            }
        }
    }

    void addFavorite(String str, Place place) throws RemoteException;

    void clearFavorites() throws RemoteException;

    void clearRecent() throws RemoteException;

    UserDataResult getFavorites(String str) throws RemoteException;

    UserDataResult getRecent() throws RemoteException;

    void registerUserDataListener(IUserDataListener iUserDataListener) throws RemoteException;

    void removeFavorite(String str, Place place) throws RemoteException;

    void unRegisterUserDataListener(IUserDataListener iUserDataListener) throws RemoteException;
}
