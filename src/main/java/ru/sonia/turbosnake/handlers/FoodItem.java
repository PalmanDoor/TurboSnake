package ru.sonia.turbosnake.handlers;

import com.jme3.scene.Geometry;

public class FoodItem {
    public final Geometry geo;
    public final boolean bad;
    public final int id;
    public final boolean isDebris;

    public FoodItem(Geometry g, boolean b, int id, boolean debris) {
        geo=g;
        bad=b;
        this.id=id;
        isDebris=debris;
    }
}
