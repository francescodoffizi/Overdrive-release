package com.telenav.app.external.model.search;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import com.telenav.app.external.LatLonData;
import com.telenav.app.external.model.search.placeextrainfo.PoiExtraInfo;
import java.util.List;

/* JADX INFO: loaded from: /tmp/claude-1338850283/-home-pal-kristensen-geodata-no/cc05901e-c414-4c8c-8231-cd74795cb1da/scratchpad/dex/classes21.dex */
public class Place implements Parcelable {
    public static final Parcelable.Creator<Place> CREATOR = new Parcelable.Creator<Place>() { // from class: com.telenav.app.external.model.search.Place.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Place createFromParcel(Parcel parcel) {
            return new Place(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public Place[] newArray(int i) {
            return new Place[i];
        }
    };
    private Address address;
    private List<Brand> brands;
    private List<Category> categories;
    private float distanceInMeter;
    private Bundle extraInfo;
    private String facetsInfo;
    private String favoriteType;
    private String formattedDistance;
    private double geoLatitude;
    private double geoLongitude;
    private double navLatitude;
    private double navLongitude;
    private List<LatLonData> navPoints;
    private String phone;
    private String placeDisplayLabel;
    private String placeId;
    private String placeName;
    private String placeType;
    private PoiExtraInfo poiExtraInfo;
    private String searchSourceType;

    public Place() {
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public Address getAddress() {
        return this.address;
    }

    public List<Brand> getBrands() {
        return this.brands;
    }

    public List<Category> getCategories() {
        return this.categories;
    }

    public float getDistanceInMeter() {
        return this.distanceInMeter;
    }

    public Bundle getExtraInfo() {
        return this.extraInfo;
    }

    public String getFacetsInfo() {
        return this.facetsInfo;
    }

    public String getFavoriteType() {
        return this.favoriteType;
    }

    public String getFormattedDistance() {
        return this.formattedDistance;
    }

    public double getGeoLatitude() {
        return this.geoLatitude;
    }

    public double getGeoLongitude() {
        return this.geoLongitude;
    }

    public double getNavLatitude() {
        return this.navLatitude;
    }

    public double getNavLongitude() {
        return this.navLongitude;
    }

    public List<LatLonData> getNavPoints() {
        return this.navPoints;
    }

    public String getPhone() {
        return this.phone;
    }

    public String getPlaceDisplayLabel() {
        return this.placeDisplayLabel;
    }

    public String getPlaceId() {
        return this.placeId;
    }

    public String getPlaceName() {
        return this.placeName;
    }

    public String getPlaceType() {
        return this.placeType;
    }

    public PoiExtraInfo getPoiExtraInfo() {
        return this.poiExtraInfo;
    }

    public String getSearchSourceType() {
        return this.searchSourceType;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public void setBrands(List<Brand> list) {
        this.brands = list;
    }

    public void setCategories(List<Category> list) {
        this.categories = list;
    }

    public void setDistanceInMeter(float f) {
        this.distanceInMeter = f;
    }

    public void setExtraInfo(Bundle bundle) {
        this.extraInfo = bundle;
    }

    public void setFacetsInfo(String str) {
        this.facetsInfo = str;
    }

    public void setFavoriteType(String str) {
        this.favoriteType = str;
    }

    public void setFormattedDistance(String str) {
        this.formattedDistance = str;
    }

    public void setGeoLatitude(double d) {
        this.geoLatitude = d;
    }

    public void setGeoLongitude(double d) {
        this.geoLongitude = d;
    }

    public void setNavLatitude(double d) {
        this.navLatitude = d;
    }

    public void setNavLongitude(double d) {
        this.navLongitude = d;
    }

    public void setNavPoints(List<LatLonData> list) {
        this.navPoints = list;
    }

    public void setPhone(String str) {
        this.phone = str;
    }

    public void setPlaceDisplayLabel(String str) {
        this.placeDisplayLabel = str;
    }

    public void setPlaceId(String str) {
        this.placeId = str;
    }

    public void setPlaceName(String str) {
        this.placeName = str;
    }

    public void setPlaceType(String str) {
        this.placeType = str;
    }

    public void setPoiExtraInfo(PoiExtraInfo poiExtraInfo) {
        this.poiExtraInfo = poiExtraInfo;
    }

    public void setSearchSourceType(String str) {
        this.searchSourceType = str;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.placeId);
        parcel.writeString(this.placeName);
        parcel.writeParcelable(this.address, i);
        parcel.writeFloat(this.distanceInMeter);
        parcel.writeTypedList(this.categories);
        parcel.writeTypedList(this.brands);
        parcel.writeString(this.favoriteType);
        parcel.writeBundle(this.extraInfo);
        parcel.writeDouble(this.geoLatitude);
        parcel.writeDouble(this.geoLongitude);
        parcel.writeDouble(this.navLatitude);
        parcel.writeDouble(this.navLongitude);
        parcel.writeString(this.phone);
        parcel.writeParcelable(this.poiExtraInfo, i);
        parcel.writeString(this.formattedDistance);
        parcel.writeString(this.placeDisplayLabel);
        parcel.writeString(this.placeType);
        parcel.writeString(this.searchSourceType);
        parcel.writeString(this.facetsInfo);
        parcel.writeTypedList(this.navPoints);
    }

    public Place(Parcel parcel) {
        this.placeId = parcel.readString();
        this.placeName = parcel.readString();
        this.address = (Address) parcel.readParcelable(Address.class.getClassLoader());
        this.distanceInMeter = parcel.readFloat();
        this.categories = parcel.createTypedArrayList(Category.CREATOR);
        this.brands = parcel.createTypedArrayList(Brand.CREATOR);
        this.favoriteType = parcel.readString();
        this.extraInfo = parcel.readBundle();
        this.geoLatitude = parcel.readDouble();
        this.geoLongitude = parcel.readDouble();
        this.navLatitude = parcel.readDouble();
        this.navLongitude = parcel.readDouble();
        this.phone = parcel.readString();
        this.poiExtraInfo = (PoiExtraInfo) parcel.readParcelable(PoiExtraInfo.class.getClassLoader());
        this.formattedDistance = parcel.readString();
        this.placeDisplayLabel = parcel.readString();
        this.placeType = parcel.readString();
        this.searchSourceType = parcel.readString();
        this.facetsInfo = parcel.readString();
        this.navPoints = parcel.createTypedArrayList(LatLonData.CREATOR);
    }
}
