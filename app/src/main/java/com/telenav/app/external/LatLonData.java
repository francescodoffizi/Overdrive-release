package com.telenav.app.external;

import android.os.Parcel;
import android.os.Parcelable;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class LatLonData implements Parcelable {
    public static final Parcelable.Creator<LatLonData> CREATOR = new Parcelable.Creator<LatLonData>() { // from class: com.telenav.app.external.LatLonData.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public LatLonData createFromParcel(Parcel parcel) {
            return new LatLonData(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public LatLonData[] newArray(int i) {
            return new LatLonData[i];
        }
    };
    private double lat;
    private double lon;

    public LatLonData(double d, double d2) {
        this.lat = d;
        this.lon = d2;
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public double getLat() {
        return this.lat;
    }

    public double getLon() {
        return this.lon;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeDouble(this.lat);
        parcel.writeDouble(this.lon);
    }

    public LatLonData(Parcel parcel) {
        this.lat = parcel.readDouble();
        this.lon = parcel.readDouble();
    }
}
