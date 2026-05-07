package ru.sonia.turbosnake.handlers;

import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.scene.Geometry;

import java.util.HashMap;
import java.util.Map;

public class BlackCube {

    public final int id;
    public final Geometry geo;
    public RigidBodyControl phy;
    public boolean active = true;
    public float glitchTimer=0f, impulseTimer=0.5f, rollSoundTimer=0f;
    public boolean chasing = false;
    public float patrolAngle=(float)(Math.random()*Math.PI*2), patrolChangeTimer=0f;
    public final Map<Integer,Float> hitCooldowns = new HashMap<>();
    public int biteCount = 0; // количество укусов — куб растёт и замедляется

    public BlackCube(int id, Geometry geo, RigidBodyControl phy) {
        this.id=id;
        this.geo=geo;
        this.phy=phy;
    }

    public void updatePhy(RigidBodyControl newPhy) { this.phy = newPhy; }
}