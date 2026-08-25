package com.telenav.app.external.model.search;

import android.os.Parcel;
import android.os.Parcelable;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class Brand implements Parcelable {
    public static final Parcelable.Creator<Brand> CREATOR = new Parcelable.Creator<Brand>() { // from class: com.telenav.app.external.model.search.Brand.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Brand createFromParcel(Parcel parcel) {
            return new Brand(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Brand[] newArray(int i) {
            return new Brand[i];
        }
    };
    private final String brandId;
    private final String brandName;

    public Brand(String str, String str2) {
        this.brandId = str;
        this.brandName = str2;
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public String getBrandId() {
        return this.brandId;
    }

    public String getBrandName() {
        return this.brandName;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.brandId);
        parcel.writeString(this.brandName);
    }

    public Brand(Parcel parcel) {
        this.brandId = parcel.readString();
        this.brandName = parcel.readString();
    }
}
