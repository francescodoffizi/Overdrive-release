package com.telenav.app.external.model.search;

import android.os.Parcel;
import android.os.Parcelable;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class Category implements Parcelable {
    public static final Parcelable.Creator<Category> CREATOR = new Parcelable.Creator<Category>() { // from class: com.telenav.app.external.model.search.Category.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Category createFromParcel(Parcel parcel) {
            return new Category(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Category[] newArray(int i) {
            return new Category[i];
        }
    };
    private int id;
    private String name;

    public Category(String str, int i) {
        this.name = str;
        this.id = i;
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public int getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.name);
        parcel.writeInt(this.id);
    }

    public Category(Parcel parcel) {
        this.name = parcel.readString();
        this.id = parcel.readInt();
    }
}
