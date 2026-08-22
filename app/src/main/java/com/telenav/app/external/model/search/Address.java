package com.telenav.app.external.model.search;

import android.os.Parcel;
import android.os.Parcelable;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class Address implements Parcelable {
    public static final Parcelable.Creator<Address> CREATOR = new Parcelable.Creator<Address>() { // from class: com.telenav.app.external.model.search.Address.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Address createFromParcel(Parcel parcel) {
            return new Address(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Address[] newArray(int i) {
            return new Address[i];
        }
    };
    private String city;
    private String countryCode;
    private String countryName;
    private String county;
    private String crossStreet;
    private String formattedAddress;
    private String fullAddress;
    private String houseNumber;
    private String postalCode;
    private String state;
    private String street;
    private String subLocality;
    private String subStreet;

    public Address() {
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public String getCity() {
        return this.city;
    }

    public String getCountryCode() {
        return this.countryCode;
    }

    public String getCountryName() {
        return this.countryName;
    }

    public String getCounty() {
        return this.county;
    }

    public String getCrossStreet() {
        return this.crossStreet;
    }

    public String getFormattedAddress() {
        return this.formattedAddress;
    }

    public String getFullAddress() {
        return this.fullAddress;
    }

    public String getHouseNumber() {
        return this.houseNumber;
    }

    public String getPostalCode() {
        return this.postalCode;
    }

    public String getState() {
        return this.state;
    }

    public String getStreet() {
        return this.street;
    }

    public String getSubLocality() {
        return this.subLocality;
    }

    public String getSubStreet() {
        return this.subStreet;
    }

    public void setCity(String str) {
        this.city = str;
    }

    public void setCountryCode(String str) {
        this.countryCode = str;
    }

    public void setCountryName(String str) {
        this.countryName = str;
    }

    public void setCounty(String str) {
        this.county = str;
    }

    public void setCrossStreet(String str) {
        this.crossStreet = str;
    }

    public void setFormattedAddress(String str) {
        this.formattedAddress = str;
    }

    public void setFullAddress(String str) {
        this.fullAddress = str;
    }

    public void setHouseNumber(String str) {
        this.houseNumber = str;
    }

    public void setPostalCode(String str) {
        this.postalCode = str;
    }

    public void setState(String str) {
        this.state = str;
    }

    public void setStreet(String str) {
        this.street = str;
    }

    public void setSubLocality(String str) {
        this.subLocality = str;
    }

    public void setSubStreet(String str) {
        this.subStreet = str;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.formattedAddress);
        parcel.writeString(this.fullAddress);
        parcel.writeString(this.houseNumber);
        parcel.writeString(this.subStreet);
        parcel.writeString(this.street);
        parcel.writeString(this.crossStreet);
        parcel.writeString(this.subLocality);
        parcel.writeString(this.city);
        parcel.writeString(this.county);
        parcel.writeString(this.state);
        parcel.writeString(this.postalCode);
        parcel.writeString(this.countryName);
        parcel.writeString(this.countryCode);
    }

    public Address(Parcel parcel) {
        this.formattedAddress = parcel.readString();
        this.fullAddress = parcel.readString();
        this.houseNumber = parcel.readString();
        this.subStreet = parcel.readString();
        this.street = parcel.readString();
        this.crossStreet = parcel.readString();
        this.subLocality = parcel.readString();
        this.city = parcel.readString();
        this.county = parcel.readString();
        this.state = parcel.readString();
        this.postalCode = parcel.readString();
        this.countryName = parcel.readString();
        this.countryCode = parcel.readString();
    }
}
