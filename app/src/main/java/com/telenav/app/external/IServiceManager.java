package com.telenav.app.external;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public interface IServiceManager extends IInterface {

    public static class Default implements IServiceManager {
        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }

        @Override // com.telenav.app.external.IServiceManager
        public void getFlowInteractionService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void getMapService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void getNavigtionService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void getSettingService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void getUserDataService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public boolean isValid(String str) throws RemoteException {
            return false;
        }

        @Override // com.telenav.app.external.IServiceManager
        public void registerFlowInteractionServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void registerMapServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void registerNavigationServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void registerSettingServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void registerUserDataServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void unRegisterFlowInteractionServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void unRegisterMapServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void unRegisterNavigationServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void unRegisterSettingServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }

        @Override // com.telenav.app.external.IServiceManager
        public void unRegisterUserDataServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
        }
    }

    public static abstract class Stub extends Binder implements IServiceManager {
        private static final String DESCRIPTOR = "com.telenav.app.external.IServiceManager";
        public static final int TRANSACTION_getFlowInteractionService = 1;
        public static final int TRANSACTION_getMapService = 4;
        public static final int TRANSACTION_getNavigtionService = 2;
        public static final int TRANSACTION_getSettingService = 3;
        public static final int TRANSACTION_getUserDataService = 5;
        public static final int TRANSACTION_isValid = 16;
        public static final int TRANSACTION_registerFlowInteractionServiceCallback = 6;
        public static final int TRANSACTION_registerMapServiceCallback = 12;
        public static final int TRANSACTION_registerNavigationServiceCallback = 8;
        public static final int TRANSACTION_registerSettingServiceCallback = 10;
        public static final int TRANSACTION_registerUserDataServiceCallback = 14;
        public static final int TRANSACTION_unRegisterFlowInteractionServiceCallback = 7;
        public static final int TRANSACTION_unRegisterMapServiceCallback = 13;
        public static final int TRANSACTION_unRegisterNavigationServiceCallback = 9;
        public static final int TRANSACTION_unRegisterSettingServiceCallback = 11;
        public static final int TRANSACTION_unRegisterUserDataServiceCallback = 15;

        public static class Proxy implements IServiceManager {
            public static IServiceManager sDefaultImpl;
            private IBinder mRemote;

            public Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            @Override // com.telenav.app.external.IServiceManager
            public void getFlowInteractionService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(1, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().getFlowInteractionService(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            @Override // com.telenav.app.external.IServiceManager
            public void getMapService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(4, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().getMapService(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void getNavigtionService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(2, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().getNavigtionService(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void getSettingService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(3, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().getSettingService(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void getUserDataService(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(5, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().getUserDataService(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public boolean isValid(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    if (!this.mRemote.transact(16, parcelObtain, parcelObtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().isValid(str);
                    }
                    parcelObtain2.readException();
                    return parcelObtain2.readInt() != 0;
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void registerFlowInteractionServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(6, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerFlowInteractionServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void registerMapServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(12, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerMapServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void registerNavigationServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(8, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerNavigationServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void registerSettingServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(10, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerSettingServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void registerUserDataServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(14, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().registerUserDataServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void unRegisterFlowInteractionServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(7, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unRegisterFlowInteractionServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void unRegisterMapServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(13, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unRegisterMapServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void unRegisterNavigationServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(9, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unRegisterNavigationServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void unRegisterSettingServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(11, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unRegisterSettingServiceCallback(iServiceInitCallback);
                    }
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.telenav.app.external.IServiceManager
            public void unRegisterUserDataServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(iServiceInitCallback != null ? iServiceInitCallback.asBinder() : null);
                    if (this.mRemote.transact(15, parcelObtain, parcelObtain2, 0) || Stub.getDefaultImpl() == null) {
                        parcelObtain2.readException();
                    } else {
                        Stub.getDefaultImpl().unRegisterUserDataServiceCallback(iServiceInitCallback);
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

        public static IServiceManager asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            return (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof IServiceManager)) ? new Proxy(iBinder) : (IServiceManager) iInterfaceQueryLocalInterface;
        }

        public static IServiceManager getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }

        public static boolean setDefaultImpl(IServiceManager iServiceManager) {
            if (Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            }
            if (iServiceManager == null) {
                return false;
            }
            Proxy.sDefaultImpl = iServiceManager;
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
                    getFlowInteractionService(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 2:
                    parcel.enforceInterface(DESCRIPTOR);
                    getNavigtionService(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 3:
                    parcel.enforceInterface(DESCRIPTOR);
                    getSettingService(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 4:
                    parcel.enforceInterface(DESCRIPTOR);
                    getMapService(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 5:
                    parcel.enforceInterface(DESCRIPTOR);
                    getUserDataService(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 6:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerFlowInteractionServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 7:
                    parcel.enforceInterface(DESCRIPTOR);
                    unRegisterFlowInteractionServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 8:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerNavigationServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 9:
                    parcel.enforceInterface(DESCRIPTOR);
                    unRegisterNavigationServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 10:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerSettingServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 11:
                    parcel.enforceInterface(DESCRIPTOR);
                    unRegisterSettingServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 12:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerMapServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 13:
                    parcel.enforceInterface(DESCRIPTOR);
                    unRegisterMapServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 14:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerUserDataServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 15:
                    parcel.enforceInterface(DESCRIPTOR);
                    unRegisterUserDataServiceCallback(IServiceInitCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 16:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean zIsValid = isValid(parcel.readString());
                    parcel2.writeNoException();
                    parcel2.writeInt(zIsValid ? 1 : 0);
                    return true;
                default:
                    return super.onTransact(i, parcel, parcel2, i2);
            }
        }
    }

    void getFlowInteractionService(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void getMapService(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void getNavigtionService(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void getSettingService(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void getUserDataService(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    boolean isValid(String str) throws RemoteException;

    void registerFlowInteractionServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void registerMapServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void registerNavigationServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void registerSettingServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void registerUserDataServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void unRegisterFlowInteractionServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void unRegisterMapServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void unRegisterNavigationServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void unRegisterSettingServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;

    void unRegisterUserDataServiceCallback(IServiceInitCallback iServiceInitCallback) throws RemoteException;
}
