package ru.sonia.turbosnake;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;
import com.jme3.scene.shape.Sphere;

import java.util.*;

/**
 * SnakePlayer — одна змейка (может быть локальной или удалённой).
 *
 * ── ГЕОМЕТРИЯ ────────────────────────────────────────────────────────────────
 *   segments  — список Geometry (по одному на каждый сегмент тела)
 *   segPos    — список Vector3f (мировые позиции тех же сегментов)
 *   Голова = segments[0] / segPos[0]
 *
 * ── ДВИЖЕНИЕ ─────────────────────────────────────────────────────────────────
 *   headingAngle  — угол направления (радианы, по оси Y)
 *   direction     — нормированный вектор направления движения
 *   currentSpeed  — текущая скорость (плавно меняется через ACCEL/DECEL)
 *   turnLeft / turnRight — входной сигнал от клавиш
 *
 *   update(tpf):
 *     1. Если игрок жмёт клавиши — поворот (TURN_SPEED рад/с)
 *     2. Если двигается — ускорение, иначе торможение
 *     3. Голова перемещается вперёд
 *     4. Хвост «ползёт» к предыдущему сегменту
 *     5. Обновляются физические тела каждого сегмента
 *     6. Обновляется нейм-тег (BitmapText над головой)
 *     7. Обновляются прилипшие фрагменты кактусов
 *
 * ── ВНУТРЕННИЙ КЛАСС ─────────────────────────────────────────────────────────
 *   CactusAttachment — прилипший к телу фрагмент кактуса:
 *     node          — Node фрагмента в сцене
 *     segmentIndex  — к какому сегменту прикреплён
 *     alongBody     — смещение вдоль тела сегмента
 *     sideBody      — смещение в сторону
 *     up            — смещение вверх
 *     localRotation — локальное вращение (сохраняется в момент удара)
 *     life          — оставшееся время жизни (секунды)
 *
 * Публичные методы:
 *   grow(am)               — добавить сегмент в хвост
 *   shrink()               — убрать последний сегмент
 *   triggerDeath(scene)    — анимация смерти + очистка физики
 *   selfCollides(minDist)  — проверка самопересечения
 *   bodyContains(pt, r)    — есть ли точка внутри тела
 *   getHeadPos()           — позиция головы
 *   getDirection()         — вектор направления
 */
public class SnakePlayer {
    private final String name;
    private final Material baseMat;
    private final Node parentNode;
    private final AssetManager assetManager;
    private final List<Geometry> segments = new ArrayList<>();
    private final List<Vector3f> segPos   = new ArrayList<>();
    private final com.jme3.bullet.PhysicsSpace physicsSpace;

				private float currentHeadY = 0.3f;
				private final List<Float> tailCurrentY = new ArrayList<>();  // плавные высоты хвоста
				private final GameState gameState;
    private float headingAngle;
    private Vector3f direction;
    private boolean turnLeft, turnRight;
    private boolean movingInput = false;
    private float currentSpeed = 0f;
    private static final float ACCEL = 18f;
    private static final float DECEL = 12f;

				private float accelMult = 1.0f;
				private float decelMult = 1.0f;
				private float turnMult  = 1.0f;
				// Новое: прилипшие куски кактусов
				private final List<CactusAttachment> cactusAttachments = new ArrayList<>();

    private int score = 0;
    private boolean dead = false;
    public static final float SEG_R = 0.27f;

				private boolean iceMode = false;          // активен ли лёд
				private Vector3f iceVelocity = new Vector3f(); // инерция скольжения
				private static final float ICE_FRICTION = 0.03f; // трение о лёд (очень низкое)
				private static final float GRAVITY_ICE = 4.5f;   // ускорение вдоль склона

    private BitmapText nameTag;
    private final Node guiRef;
    private final Camera camRef;

					public SnakePlayer(String name, Vector3f startPos, float startAngle,
														 Material mat, Node parent, AssetManager am,
														 Node guiNode, Camera cam, com.jme3.bullet.PhysicsSpace space,
														 GameState gameState) {
        this.name=name; this.baseMat=mat; this.parentNode=parent;
        this.assetManager=am; this.guiRef=guiNode; this.camRef=cam;
        this.physicsSpace=space; this.headingAngle=startAngle;
        this.direction=calcDir(headingAngle);
						this.gameState = gameState;

        Vector3f back = direction.negate();
        for (int i=0;i<4;i++) addSegment(startPos.add(back.mult(i*0.55f)));

						for (int i = 0; i < segPos.size(); i++) {
								Vector3f pos = segPos.get(i);
								float terrainHeight = gameState.getSurfaceHeight(pos.x, pos.z);
								pos.y = terrainHeight + SEG_R; // SEG_R = 0.27f, можно + 0.3f
								segments.get(i).setLocalTranslation(pos);
						}
						currentHeadY = segPos.get(0).y;

        BitmapFont font = TurboSnake.loadFont(am);
        nameTag = new BitmapText(font);
        nameTag.setSize(16); nameTag.setText(name);
        nameTag.setColor(getColorFromMat(mat));
        guiNode.attachChild(nameTag);
    }

    private ColorRGBA getColorFromMat(Material m) {
        Object c = m.getParamValue("Color");
        if (c instanceof ColorRGBA) return (ColorRGBA)c;
        Object d = m.getParamValue("Diffuse");
        return d instanceof ColorRGBA ? (ColorRGBA)d : ColorRGBA.White;
    }

    private void addSegment(Vector3f pos) {
        Geometry g = new Geometry("Seg"+segments.size(), new Sphere(12,12,SEG_R));
        Material sm = baseMat.clone();
        float factor = Math.max(0.5f, 1f - segments.size()*0.04f);
        // Поддержка Unshaded ("Color") и Lighting ("Diffuse")
        Object cv = baseMat.getParamValue("Color");
        if (cv instanceof ColorRGBA c) {
            sm.setColor("Color", new ColorRGBA(c.r*factor, c.g*factor, c.b*factor, 1f));
        } else {
            Object dv = baseMat.getParamValue("Diffuse");
            if (dv instanceof ColorRGBA d) {
                sm.setColor("Diffuse", new ColorRGBA(d.r*factor, d.g*factor, d.b*factor, 1f));
                sm.setColor("Ambient", new ColorRGBA(d.r*factor*0.25f, d.g*factor*0.25f, d.b*factor*0.25f, 1f));
            }
        }
        g.setMaterial(sm); g.setLocalTranslation(pos.clone());
						g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
        parentNode.attachChild(g);

        if (physicsSpace!=null && segments.size()>0) {
            com.jme3.bullet.control.RigidBodyControl phy =
                    new com.jme3.bullet.control.RigidBodyControl(
                            new com.jme3.bullet.collision.shapes.SphereCollisionShape(SEG_R), 1f);
            phy.setKinematic(true); g.addControl(phy); physicsSpace.add(phy);
        }
        segments.add(g); segPos.add(pos.clone());
						tailCurrentY.add(pos.y);
    }

    public void setMoving(boolean v) { this.movingInput=v; }
    public boolean isMoving() { return movingInput; }

				public void update(float tpf, float maxSpeed, float turnSpeed, float spacing) {
						if (dead) return;
						if (movingInput) currentSpeed = Math.min(maxSpeed, currentSpeed + ACCEL * accelMult * tpf);
						else             currentSpeed = Math.max(0f,         currentSpeed - DECEL * decelMult * tpf);

						if (iceMode) {
								// === ЛЕДЯНОЙ РЕЖИМ ===
								// 1. Инерция сохраняется: скорость отдельно от ввода
								Vector3f headPos = segPos.get(0);

								// Вычисляем наклон поверхности под головой
								float slopeX = gameState.getSurfaceHeight(headPos.x + 0.5f, headPos.z)
														 - gameState.getSurfaceHeight(headPos.x - 0.5f, headPos.z);
								float slopeZ = gameState.getSurfaceHeight(headPos.x, headPos.z + 0.5f)
														 - gameState.getSurfaceHeight(headPos.x, headPos.z - 0.5f);
								Vector3f gravitySlope = new Vector3f(-slopeX, 0f, -slopeZ).normalizeLocal()
																				 .multLocal(GRAVITY_ICE * tpf);

								// Влияние вперёд (направление взгляда) добавляет тягу
								if (movingInput) {
										iceVelocity.addLocal(direction.mult(ACCEL * tpf));
								}

								// Гравитационное ускорение вдоль склона
								iceVelocity.addLocal(gravitySlope);

								// Трение (очень слабое): замедляем, но только если скорость не нулевая
								float friction = ICE_FRICTION * tpf;
								// синхронизируем направление взгляда с фактическим движением
								if (iceVelocity.lengthSquared() > 0.01f) {
										headingAngle = (float) Math.atan2(iceVelocity.x, iceVelocity.z);
										direction = calcDir(headingAngle);
								} else {
										iceVelocity.set(0,0,0);
								}

								// Ограничение максимальной скорости (чтобы не разгонялась бесконечно)
								float iceMaxSpeed = GameState.SPEED * 1.8f;
								if (iceVelocity.length() > iceMaxSpeed) {
										iceVelocity.normalizeLocal().multLocal(iceMaxSpeed);
								}

								// Применяем поворот плавно (с инерцией): 'direction' плавно доворачиваем к желаемому
								if (turnLeft || turnRight) {
										float turnAngle = (turnLeft ? GameState.TURN_SPEED : -GameState.TURN_SPEED) * tpf * 0.6f; // очень медленный поворот
										Quaternion rot = new Quaternion().fromAngleAxis(turnAngle, Vector3f.UNIT_Y);
										iceVelocity = rot.mult(iceVelocity);
										headingAngle += turnAngle;
								}

								// Перемещаем голову согласно iceVelocity
								Vector3f newHead = headPos.add(iceVelocity.mult(tpf));
								float targetY = gameState.getSurfaceHeight(newHead.x, newHead.z) + SEG_R + 0.1f;
								currentHeadY += (targetY - currentHeadY) * Math.min(1f, 8f * tpf); // плавное изменение высоты
								newHead.y = currentHeadY;
								segPos.get(0).set(newHead);
								segments.get(0).setLocalTranslation(newHead);
								tailCurrentY.set(0, currentHeadY);

								// Обновляем хвост как обычно (с прилипанием к поверхности)
								for (int i = 1; i < segPos.size(); i++) {
										Vector3f prev = segPos.get(i - 1), cur = segPos.get(i);
										Vector3f diff = prev.subtract(cur);
										float dist = diff.length();
										if (dist > GameState.SEG_SPACING) {
												cur.addLocal(diff.normalize().mult(dist - GameState.SEG_SPACING));
												float curTargetY = gameState.getSurfaceHeight(cur.x, cur.z) + SEG_R;
												float curCurrentY = tailCurrentY.get(i);
												curCurrentY += (curTargetY - curCurrentY) * Math.min(1f, 8f * tpf);
												cur.y = curCurrentY;
												tailCurrentY.set(i, curCurrentY);
												segments.get(i).setLocalTranslation(cur);
										}
								}

								updateCactusAttachments(tpf);
								return; // выходим, не выполняя старую логику движения
						}

						float effectiveTurnSpeed = turnSpeed * (currentSpeed / maxSpeed);
						if (turnLeft)  headingAngle += effectiveTurnSpeed * tpf;
						if (turnRight) headingAngle -= effectiveTurnSpeed * tpf;
						direction = calcDir(headingAngle);

						if (currentSpeed < 0.01f) return;

						// === Голова с плавной высотой ===
						Vector3f newHead = segPos.get(0).add(direction.mult(currentSpeed * tpf));
						float targetY = gameState.getSurfaceHeight(newHead.x, newHead.z) + SEG_R + 0.1f;
						float maxDy = 30f * tpf;                           // макс. вертикальная скорость
						if (Math.abs(targetY - currentHeadY) < maxDy) {
								currentHeadY = targetY;
						} else {
								currentHeadY += Math.signum(targetY - currentHeadY) * maxDy;
						}
						newHead.y = currentHeadY;
						segPos.get(0).set(newHead);
						segments.get(0).setLocalTranslation(newHead);
						tailCurrentY.set(0, currentHeadY);

						// === Хвост с плавной высотой ===
						for (int i = 1; i < segPos.size(); i++) {
								Vector3f prev = segPos.get(i - 1), cur = segPos.get(i);
								Vector3f diff = prev.subtract(cur);
								float dist = diff.length();
								if (dist > spacing) {
										cur.addLocal(diff.normalize().mult(dist - spacing));
										// плавная высота для этого сегмента
										float curTargetY = gameState.getSurfaceHeight(cur.x, cur.z) + SEG_R;
										float curCurrentY = tailCurrentY.get(i);
										float curMaxDy = 10f * tpf;
										if (Math.abs(curTargetY - curCurrentY) < curMaxDy) {
												curCurrentY = curTargetY;
										} else {
												curCurrentY += Math.signum(curTargetY - curCurrentY) * curMaxDy;
										}
										cur.y = curCurrentY;
										tailCurrentY.set(i, curCurrentY);
										segments.get(i).setLocalTranslation(cur);
								}
						}

						updateCactusAttachments(tpf);
				}

				// В SnakePlayer.update добавить расчёт pitch
				private float calculatePitch(Vector3f headPos) {
						float dx = direction.x * 0.5f;
						float dz = direction.z * 0.5f;
						float yAhead = gameState.getSurfaceHeight(headPos.x + dx, headPos.z + dz);
						float dy = yAhead - headPos.y;
						return (float) Math.atan2(dy, 0.5f) * 0.5f;
				}

				public void setPhysicsMult(float accel, float decel, float turn) {
						this.accelMult = accel;
						this.decelMult = decel;
						this.turnMult  = turn;
				}

				public void enableIceMode(boolean enable) {
						this.iceMode = enable;
						if (enable) {
								// начинаем скользить с текущей скоростью и направлением
								iceVelocity.set(direction.mult(currentSpeed));
						} else {
								iceVelocity.set(0,0,0);
						}
				}

    public void updateNameTag(Camera cam) {
        if (nameTag==null||dead||segPos.isEmpty()) return;
        Vector3f worldPos = segPos.get(0).add(0,1.4f,0);
        Vector3f screen = cam.getScreenCoordinates(worldPos);
        if (screen.z<0||screen.z>1) { nameTag.setLocalTranslation(-9999,0,0); return; }
        nameTag.setLocalTranslation(screen.x-nameTag.getLineWidth()/2, screen.y, 1);
    }

    public void applyNetState(float x, float y, float z, float angle, int sc, int remoteLen, boolean isDead) {
        if (!segPos.isEmpty()) {
            Vector3f newHead = new Vector3f(x,y,z);
            headingAngle=angle; direction=calcDir(headingAngle);
            segPos.get(0).set(newHead); segments.get(0).setLocalTranslation(newHead);
            while (segments.size()<remoteLen) grow(assetManager);
            while (segments.size()>remoteLen&&segments.size()>2) shrink();
            float spacing=0.55f;
            for (int i=1;i<segPos.size();i++) {
                Vector3f prev=segPos.get(i-1), cur=segPos.get(i);
                Vector3f diff=prev.subtract(cur); float dist=diff.length();
                if (dist>spacing) { cur.addLocal(diff.normalize().mult(dist-spacing)); segments.get(i).setLocalTranslation(cur); }
            }
        }
        score=sc;
        if (isDead&&!dead) triggerDeathRemote(parentNode.getParent());
    }

				public int getClosestSegmentIndex(Vector3f worldPos) {
						if (segPos.isEmpty()) return -1;
						int closest = 0;
						float minDist = worldPos.distance(segPos.get(0));
						for (int i = 1; i < segPos.size(); i++) {
								float d = worldPos.distance(segPos.get(i));
								if (d < minDist) {
										minDist = d;
										closest = i;
								}
						}
						return closest;
				}

				// Приклеить фрагмент кактуса к змее
				public void attachCactusFragment(Node fragNode, Vector3f worldPos) {
						RigidBodyControl phy = fragNode.getControl(RigidBodyControl.class);
						if (phy != null && physicsSpace != null) {
								physicsSpace.remove(phy);
								fragNode.removeControl(phy);
						}
						fragNode.removeFromParent();
						parentNode.attachChild(fragNode);

						int segIdx = getClosestSegmentIndex(worldPos);
						if (segIdx < 0) segIdx = 0;
						Vector3f segCenter = segPos.get(segIdx);

						// Локальная система координат сегмента
						Vector3f bodyDir;
						if (segIdx < segPos.size() - 1) {
								bodyDir = segPos.get(segIdx + 1).subtract(segPos.get(segIdx)).normalizeLocal();
						} else if (segIdx > 0) {
								bodyDir = segPos.get(segIdx).subtract(segPos.get(segIdx - 1)).normalizeLocal();
						} else {
								bodyDir = direction;
						}
						Vector3f right = new Vector3f(bodyDir.z, 0, -bodyDir.x).normalizeLocal();

						// Проецируем точку касания на локальные оси
						Vector3f toFrag = worldPos.subtract(segCenter);
						float along = toFrag.dot(bodyDir);
						float side  = toFrag.dot(right);
						float up    = toFrag.y * 0.15f;

						// Радиус сегмента + небольшой контактный зазор
						float placementDist = SEG_R + 0.12f;

						// Нормализуем направление от центра сегмента к точке контакта
						Vector3f dirToFrag = toFrag.clone();
						float len = dirToFrag.length();
						if (len < 0.001f) {
								dirToFrag = Vector3f.UNIT_Y;
						} else {
								dirToFrag.divideLocal(len);
						}

						// Вычисляем смещение строго по направлению от центра на нужное расстояние
						Vector3f offset = dirToFrag.mult(placementDist);
						// Перепроецируем на локальные оси? Нет, мы уже используем готовый offset.
						// Но чтобы локальные координаты (along, side, up) соответствовали этому offset,
						// пересчитаем их заново относительно локальных осей:
						// (этот шаг избыточен, можно оставить как есть, но тогда старые along/side не будут
						// точно совпадать с новым offset – однако это не критично, так как фрагмент ставится
						// по offset, а along/side теперь не используются? Нет, они используются в update.
						// Значит их нужно обновить.)
						along = offset.dot(bodyDir);
						side  = offset.dot(right);
						up    = offset.y;

						// ---- НОВОЕ: фиксируем локальное вращение ----
						// Берём текущую мировую ориентацию фрагмента
						Quaternion worldRot = fragNode.getWorldRotation().clone();
						// Получаем мировую ориентацию сегмента (ось Z по bodyDir, ось X по right, ось Y вверх)
						Quaternion segWorldRot = new Quaternion();
						segWorldRot.lookAt(bodyDir, Vector3f.UNIT_Y);
						// Вычисляем относительное вращение: localRot = inv(segWorldRot) * worldRot
						Quaternion localRot = segWorldRot.inverse().mult(worldRot);
						// ------------------------------------------

						cactusAttachments.add(new CactusAttachment(fragNode, segIdx, along, side, up,
										localRot, 8.0f));
				}

				// обновление прилипших фрагментов (вызывать в update)
				private void updateCactusAttachments(float tpf) {
						for (int i = cactusAttachments.size() - 1; i >= 0; i--) {
								CactusAttachment att = cactusAttachments.get(i);
								att.life -= tpf;
								if (att.life <= 0f || att.segmentIndex >= segPos.size()) {
										att.node.removeFromParent();
										cactusAttachments.remove(i);
										continue;
								}

								Vector3f segCenter = segPos.get(att.segmentIndex);
								Vector3f bodyDir;
								if (att.segmentIndex < segPos.size() - 1) {
										bodyDir = segPos.get(att.segmentIndex + 1).subtract(segCenter).normalizeLocal();
								} else if (att.segmentIndex > 0) {
										bodyDir = segCenter.subtract(segPos.get(att.segmentIndex - 1)).normalizeLocal();
								} else {
										bodyDir = direction;
								}
								if (bodyDir.lengthSquared() < 0.0001f) continue;
								Vector3f right = new Vector3f(bodyDir.z, 0, -bodyDir.x).normalizeLocal();

								// Позиция
								Vector3f worldOffset = bodyDir.mult(att.alongBody)
												.addLocal(right.mult(att.sideBody))
												.addLocal(0, att.up, 0);
								att.node.setLocalTranslation(segCenter.add(worldOffset));

								// Вращение: восстанавливаем мировое вращение = segWorldRot * localRot
								Quaternion segWorldRot = new Quaternion();
								segWorldRot.lookAt(bodyDir, Vector3f.UNIT_Y);
								att.node.setLocalRotation(segWorldRot.mult(att.localRotation));
						}
				}

				// очистка прилипших фрагментов (при смерти/выходе)
				private void cleanupCactusAttachments() {
						for (CactusAttachment att : cactusAttachments) {
								att.node.removeFromParent();
						}
						cactusAttachments.clear();
				}

				// Внутри SnakePlayer, класс CactusAttachment
				private static class CactusAttachment {
						Node node;
						int segmentIndex;
						float alongBody, sideBody, up;
						Quaternion localRotation;          // новое поле
						float life;

						// Обновлённый конструктор — 7 параметров
						CactusAttachment(Node node, int idx, float along, float side, float up,
														 Quaternion localRot, float life) {
								this.node = node;
								this.segmentIndex = idx;
								this.alongBody = along;
								this.sideBody = side;
								this.up = up;
								this.localRotation = localRot;
								this.life = life;
						}
				}

    public void triggerDeath(Node scene) {
        if (dead) return; dead=true;
        if (nameTag!=null) { guiRef.detachChild(nameTag); nameTag=null; }
        for (Geometry seg:segments) {
            com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
            if (phy!=null&&physicsSpace!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
            parentNode.detachChild(seg);
        }
        segments.clear(); segPos.clear();
    }

    public void triggerDeathRemote(Node scene) { triggerDeath(scene); }

    public List<Vector3f> getSegmentPositions() {
        List<Vector3f> result = new ArrayList<>();
        for (Vector3f p:segPos) result.add(p.clone());
        return result;
    }

    public void grow(AssetManager am) {
        if (segPos.isEmpty()) return;
        addSegment(segPos.get(segPos.size()-1).clone());
    }

    public void shrink() {
        if (segments.size()<=2) return;
        int idx=segments.size()-1;
        Geometry seg=segments.get(idx);
        com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
        if (phy!=null&&physicsSpace!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
        parentNode.detachChild(seg); segments.remove(idx); segPos.remove(idx);
    }


    public void removeSegmentsAtWorldPos(Vector3f worldPos, float radius) {
        if (dead || segments.size() <= 2) return;
        for (int i = segments.size() - 1; i >= 1; i--) {
            if (segPos.get(i).distance(worldPos) < radius) {
                Geometry seg = segments.get(i);
                com.jme3.bullet.control.RigidBodyControl phy = seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                if (phy != null && physicsSpace != null) { phy.setEnabled(false); physicsSpace.remove(phy); }
                parentNode.detachChild(seg);
                segments.remove(i);
                segPos.remove(i);
            }
        }
    }

    public boolean selfCollides(float minDist) {
        if (segPos.size()<4) return false;
        Vector3f h=segPos.get(0);
        for (int i=3;i<segPos.size();i++) if (h.distance(segPos.get(i))<minDist) return true;
        return false;
    }

    public boolean bodyContains(Vector3f point, float radius) {
        for (int i=1;i<segPos.size();i++) if (point.distance(segPos.get(i))<radius) return true;
        return false;
    }

    public void cleanup(Node gui) {
						cleanupCactusAttachments(); // <-- сначала удаляем прилипшие фрагменты
        if (nameTag!=null) { gui.detachChild(nameTag); nameTag=null; }
        if (!dead&&physicsSpace!=null) {
            for (Geometry seg:segments) {
                com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                if (phy!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
            }
        }
    }

    private static Vector3f calcDir(float angle) { return new Vector3f(FastMath.sin(angle),0,FastMath.cos(angle)); }

    public void setTurnLeft(boolean v)  { turnLeft=v; }
    public void setTurnRight(boolean v) { turnRight=v; }
    public boolean isTurnLeft()  { return turnLeft; }
    public boolean isTurnRight() { return turnRight; }
    public Vector3f getHeadPos() { return segPos.isEmpty()?Vector3f.ZERO:segPos.get(0); }
    public Vector3f getDirection() { return direction; }
    public float getHeadingAngle() { return headingAngle; }
    public String getName()  { return name; }
    public int getScore()    { return score; }
    public int getLength()   { return segments.size(); }
    public boolean isDead()  { return dead; }
    public void addScore(int v) { score+=v; }
}
