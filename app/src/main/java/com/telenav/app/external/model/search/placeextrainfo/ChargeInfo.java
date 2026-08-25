package com.telenav.app.external.model.search.placeextrainfo;

import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class ChargeInfo implements Parcelable {
    public static final Parcelable.Creator<ChargeInfo> CREATOR = new Parcelable.Creator<ChargeInfo>() { // from class: com.telenav.app.external.model.search.placeextrainfo.ChargeInfo.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public ChargeInfo createFromParcel(Parcel parcel) {
            return new ChargeInfo(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public ChargeInfo[] newArray(int i) {
            return new ChargeInfo[i];
        }
    };
    private int available;
    private String power;
    private int total;

    public ChargeInfo() {
    }

    public static ChargeInfo fromJson(String str) {
        ChargeInfo chargeInfo = new ChargeInfo();
        try {
            JSONObject jSONObject = new JSONObject(str);
            if (jSONObject.has("total")) {
                chargeInfo.total = jSONObject.optInt("total");
            }
            if (jSONObject.has("available")) {
                chargeInfo.available = jSONObject.optInt("available");
            }
            if (jSONObject.has("power")) {
                chargeInfo.power = jSONObject.optString("power");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return chargeInfo;
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public int getAvailable() {
        return this.available;
    }

    public String getPower() {
        return this.power;
    }

    public int getTotal() {
        return this.total;
    }

    public void setAvailable(int i) {
        this.available = i;
    }

    public void setPower(String str) {
        this.power = str;
    }

    public void setTotal(int i) {
        this.total = i;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("total", this.total);
        jSONObject.put("available", this.available);
        jSONObject.put("power", this.power);
        return jSONObject;
    }

    public String toString() {
        return "ChargeInfo{total=" + this.total + ", available=" + this.available + ", power=" + this.power + '}';
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeInt(this.total);
        parcel.writeInt(this.available);
        parcel.writeString(this.power);
    }

    public ChargeInfo(Parcel parcel) {
        this.total = parcel.readInt();
        this.available = parcel.readInt();
        this.power = parcel.readString();
    }
}
