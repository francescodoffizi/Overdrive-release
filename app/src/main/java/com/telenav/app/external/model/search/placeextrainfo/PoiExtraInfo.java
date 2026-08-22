package com.telenav.app.external.model.search.placeextrainfo;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class PoiExtraInfo implements Parcelable {
    public static final Parcelable.Creator<PoiExtraInfo> CREATOR = new Parcelable.Creator<PoiExtraInfo>() { // from class: com.telenav.app.external.model.search.placeextrainfo.PoiExtraInfo.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public PoiExtraInfo createFromParcel(Parcel parcel) {
            return new PoiExtraInfo(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public PoiExtraInfo[] newArray(int i) {
            return new PoiExtraInfo[i];
        }
    };
    private boolean busiState;
    private String busiTime;
    private List<ChargeInfo> content;
    private String payment;

    public PoiExtraInfo() {
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public boolean getBusiState() {
        return this.busiState;
    }

    public String getBusiTime() {
        return this.busiTime;
    }

    public List<ChargeInfo> getContent() {
        return this.content;
    }

    public String getPayment() {
        return this.payment;
    }

    public void setBusiState(boolean z) {
        this.busiState = z;
    }

    public void setBusiTime(String str) {
        this.busiTime = str;
    }

    public void setContent(List<ChargeInfo> list) {
        this.content = list;
    }

    public void setPayment(String str) {
        this.payment = str;
    }

    public String toString() {
        return "PoiExtraInfo{busiState='" + this.busiState + "', busiTime=" + this.busiTime + ", payment='" + this.payment + "', content='" + this.content + '}';
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeByte(this.busiState ? (byte) 1 : (byte) 0);
        parcel.writeString(this.busiTime);
        parcel.writeString(this.payment);
        parcel.writeTypedList(this.content);
    }

    public PoiExtraInfo(Parcel parcel) {
        this.busiState = parcel.readByte() != 0;
        this.busiTime = parcel.readString();
        this.payment = parcel.readString();
        this.content = parcel.createTypedArrayList(ChargeInfo.CREATOR);
    }
}
