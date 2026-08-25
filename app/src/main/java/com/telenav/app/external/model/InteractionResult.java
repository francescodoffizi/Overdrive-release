package com.telenav.app.external.model;

import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class InteractionResult implements Parcelable {
    public static final Parcelable.Creator<InteractionResult> CREATOR = new Parcelable.Creator<InteractionResult>() { // from class: com.telenav.app.external.model.InteractionResult.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public InteractionResult createFromParcel(Parcel parcel) {
            return new InteractionResult(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public InteractionResult[] newArray(int i) {
            return new InteractionResult[i];
        }
    };
    private int code;

    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {
        public static final int RESULT_APP_NOT_READY = 10026;
        public static final int RESULT_DUPLICATE_WAYPOINT_ERROR = 10018;
        public static final int RESULT_FAILED = 10014;
        public static final int RESULT_FAILED_SDK_INIT_ERROR = -2;
        public static final int RESULT_NETWORK_ERROR = 10011;
        public static final int RESULT_OUT_OF_MAX_DISTANCE_ERROR = 10025;
        public static final int RESULT_OUT_OF_MAX_NUMBER_ERROR = 10017;
        public static final int RESULT_PARAM_ERROR = 10010;
        public static final int RESULT_SUCCESS = 10000;
        public static final int RESULT_UNSUPPORTED_TYPE_ERROR = 10013;
    }

    public InteractionResult() {
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public int getCode() {
        return this.code;
    }

    public void setCode(int i) {
        this.code = i;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(this.code);
    }

    public InteractionResult(Parcel parcel) {
        this.code = parcel.readInt();
    }
}
