package ru.sonia.turbosnake.handlers;

import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.scene.Geometry;

import java.util.ArrayList;
import java.util.List;

public class CactusData {

    public final Geometry trunkGeo;
    public final RigidBodyControl trunkPhy;
    public Geometry armGeo;                   // может быть null
    public final List<Geometry> spines = new ArrayList<>(); // колючки на месте
    public final float origX, origZ;
    public float respawnTimer = 0f;
    public boolean queuedForRespawn = false;
    public boolean hit = false;
    public final List<Geometry> fragments = new ArrayList<>();
    public final List<Float> fragmentTimers = new ArrayList<>();
    public static final float FRAGMENT_LIFETIME = 8f;

    public CactusData(Geometry trunk, RigidBodyControl phy, float x, float z) {
        trunkGeo = trunk;
        trunkPhy = phy;
        origX = x;
        origZ = z;
    }
}