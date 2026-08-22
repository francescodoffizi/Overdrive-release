package com.telenav.app.external.model.userservice;

import android.os.Parcel;
import android.os.Parcelable;
import com.telenav.app.external.model.InteractionResult;
import com.telenav.app.external.model.search.Place;
import java.util.List;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class UserDataResult extends InteractionResult {
    public static final Parcelable.Creator<UserDataResult> CREATOR = new Parcelable.Creator<UserDataResult>() { // from class: com.telenav.app.external.model.userservice.UserDataResult.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public UserDataResult createFromParcel(Parcel parcel) {
            return new UserDataResult(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public UserDataResult[] newArray(int i) {
            return new UserDataResult[i];
        }
    };
    private List<Place> data;
    private int maxCount;
    private String type;

    public UserDataResult() {
    }

    @Override // com.telenav.app.external.model.InteractionResult, android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public List<Place> getData() {
        return this.data;
    }

    public int getMaxCount() {
        return this.maxCount;
    }

    public String getType() {
        return this.type;
    }

    public void setData(List<Place> list) {
        this.data = list;
    }

    public void setMaxCount(int i) {
        this.maxCount = i;
    }

    public void setType(String str) {
        this.type = str;
    }

    @Override // com.telenav.app.external.model.InteractionResult, android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        super.writeToParcel(parcel, i);
        parcel.writeTypedList(this.data);
        parcel.writeInt(this.maxCount);
        parcel.writeString(this.type);
    }

    public UserDataResult(Parcel parcel) {
        super(parcel);
        this.data = parcel.createTypedArrayList(Place.CREATOR);
        this.maxCount = parcel.readInt();
        this.type = parcel.readString();
    }
}
