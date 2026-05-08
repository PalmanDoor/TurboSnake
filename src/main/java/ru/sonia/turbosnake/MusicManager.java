package ru.sonia.turbosnake;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioNode;
import com.jme3.audio.AudioData.DataType;
import com.jme3.audio.AudioSource;
import com.jme3.scene.*;

/**
 * MusicManager — синглтон-менеджер фоновой музыки.
 *
 * Гарантирует, что одновременно играет не более одного трека.
 * При повторном запросе того же трека просто обновляет громкость.
 * Методы:
 *   play(am, root, track, volume) — запустить/переключить трек
 *   setVolume(v)                  — изменить громкость на лету
 *   stop(root)                    — остановить и удалить из сцены
 */
public class MusicManager {
    private static AudioNode currentBgMusic = null;
    private static String currentTrack = "";

    static void play(AssetManager am, Node root, String track, float volume) {
        // Если уже играет этот трек — просто обновляем громкость
        if (track.equals(currentTrack) && currentBgMusic != null
                && currentBgMusic.getStatus() == AudioSource.Status.Playing) {
            currentBgMusic.setVolume(volume);
            return;
        }
        stop(root);
        try {
            currentBgMusic = new AudioNode(am, track, DataType.Stream);
            currentBgMusic.setPositional(false);
            currentBgMusic.setLooping(true);
            currentBgMusic.setVolume(volume);
            root.attachChild(currentBgMusic);
            currentBgMusic.play();
            currentTrack = track;
        } catch (Exception e) {
            System.out.println("[Music] " + track + " not found");
        }
    }

    static void setVolume(float v) {
        if (currentBgMusic != null) currentBgMusic.setVolume(v);
    }

    static void stop(Node root) {
        if (currentBgMusic != null) {
            currentBgMusic.stop();
            try { root.detachChild(currentBgMusic); } catch (Exception ignore) {}
            currentBgMusic = null;
            currentTrack = "";
        }
    }
}