package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.ModeProperty;
import myau.property.properties.BooleanProperty;
// FIX: Changed NumberProperty to DoubleProperty (assuming standard naming)
import myau.property.properties.DoubleProperty;
import myau.property.properties.IntegerProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.Vec3;
import net.minecraft.util.MathHelper;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.ArrayDeque;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random RANDOM = new Random();

    // Universal bypass modes
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"SMART", "AGGRESSIVE", "DEFENSIVE", "GHOST"});
    public final ModeProperty acTarget = new ModeProperty("AC_Target", 0, new String[]{"AUTO", "GRIM", "VULCAN", "VERUS", "AAC", "POLAR", "NCP"});
    public final BooleanProperty humanization = new BooleanProperty("Humanization", true);
    public final BooleanProperty rotationCheck = new BooleanProperty("RotationCheck", true);
    // FIX: NumberProperty -> DoubleProperty for decimal values
    public final DoubleProperty randomization = new DoubleProperty("Randomization", 0.15, 0.0, 0.3, 0.01);
    // FIX: NumberProperty -> IntegerProperty for whole numbers
    public final IntegerProperty maxCps = new IntegerProperty("MaxCPS", 12, 8, 20, 1);

    // State tracking
    private boolean sprintState = false;
    private boolean set = false;
    private double savedSlowdown = 0.0;
    private int blockedHits = 0;
    private int allowedHits = 0;
    
    // Universal evasion variables
    private double dynamicDistanceThreshold;
    private double dynamicAngleThreshold;
    private int dynamicHurtBuffer;
    private double randomizationFactor;
    private int maxConsecutiveBlocks;
    
    // Transaction tracking (Grim/Polar)
    private int transactionId = 0;
    private long lastTransactionTime = 0;
    private int transactionCounter = 0;
    private boolean awaitingTransaction = false;
    
    // Pattern breaking (AAC/Vulcan)
    private int consecutiveBlocks = 0;
    private int hitCounter = 0;
    private boolean forceNextHit = false;
    private List<Long> clickTimestamps = new ArrayList<>();
    private double lastYaw = 0;
    private double lastPitch = 0;
    private double yawDelta = 0;
    private double pitchDelta = 0;
    
    // Rotation smoothing (AAC/Matrix)
    private float targetYaw = 0;
    private float targetPitch = 0;
    private float currentYaw = 0;
    private float currentPitch = 0;
    private int rotationTicks = 0;
    
    // Ghost mode variables
    private int ghostHitsRemaining = 0;
    private int ghostPattern = 0;
    private long lastGhostSwitch = 0;
    
    // Motion preservation system
    private double preservedMotionX = 0;
    private double preservedMotionZ = 0;
    private int motionPreserveTicks = 0;
    
    // Velocity tracking for modern ACs
    private Deque<Double> velocityHistory = new ArrayDeque<>(10);
    private double lastVelocity = 0;
    
    // CPS variance tracking
    private long lastClickTime = 0;
    private double avgCps = 0;

    public HitSelect() {
        super("HitSelect", false);
        initializeDynamicValues();
    }
    
    private void initializeDynamicValues() {
        this.dynamicDistanceThreshold = 2.2 + (RANDOM.nextDouble() * 0.6);
        this.dynamicAngleThreshold = 50.0 + (RANDOM.nextDouble() * 25.0);
        this.dynamicHurtBuffer = 1 + RANDOM.nextInt(2);
        this.randomizationFactor = 0.8 + (RANDOM.nextDouble() * 0.2);
        this.maxConsecutiveBlocks = 2 + RANDOM.nextInt(2);
        this.ghostPattern = 3 + RANDOM.nextInt(3);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;

        if (event.getType() == EventType.PRE) {
            updateRotations();
            updateACDetection();
            updateMotionPreservation();
        } else if (event.getType() == EventType.POST) {
            this.resetMotion();
            if (hitCounter++ % 40 == 0) {
                adjustRandomization();
                cleanOldClicks();
            }
        }
    }
    
    private void updateRotations() {
        if (!rotationCheck.getValue() || mc.thePlayer == null) return;
        
        currentYaw = mc.thePlayer.rotationYaw;
        currentPitch = mc.thePlayer.rotationPitch;
        
        if (rotationTicks > 0) {
            float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
            float pitchDiff = targetPitch - currentPitch;
            
            float yawStep = yawDiff * 0.3f;
            float pitchStep = pitchDiff * 0.3f;
            
            yawStep += (RANDOM.nextFloat() - 0.5f) * 0.5f;
            pitchStep += (RANDOM.nextFloat() - 0.5f) * 0.5f;
            
            mc.thePlayer.rotationYaw += yawStep;
            mc.thePlayer.rotationPitch += pitchStep;
            rotationTicks--;
        }
    }
    
    private void updateACDetection() {
        if (acTarget.getValue() != 0) return;
        
        if (transactionCounter > 10 && System.currentTimeMillis() - lastTransactionTime < 100) {
            randomizationFactor = 0.75;
        }
    }
    
    private void updateMotionPreservation() {
        if (motionPreserveTicks > 0) {
            motionPreserveTicks--;
            if (motionPreserveTicks == 0) {
                mc.thePlayer.motionX = preservedMotionX;
                mc.thePlayer.motionZ = preservedMotionZ;
            } else {
                double blend = 1.0 - ((double) motionPreserveTicks / 3.0);
                mc.thePlayer.motionX = mc.thePlayer.motionX * (1 - blend) + preservedMotionX * blend;
                mc.thePlayer.motionZ = mc.thePlayer.motionZ * (1 - blend) + preservedMotionZ * blend;
            }
        }
    }
    
    private void adjustRandomization() {
        this.dynamicDistanceThreshold += (RANDOM.nextDouble() - 0.5) * 0.15;
        this.dynamicDistanceThreshold = Math.max(2.0, Math.min(3.0, this.dynamicDistanceThreshold));
        
        this.randomizationFactor = 0.75 + (RANDOM.nextDouble() * 0.25);
        this.maxConsecutiveBlocks = 2 + RANDOM.nextInt(3);
    }
    
    private void cleanOldClicks() {
        long now = System.currentTimeMillis();
        clickTimestamps.removeIf(time -> now - time > 1000);
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) return;

        if (event.getPacket() instanceof C0FPacketConfirmTransaction) {
            handleTransaction((C0FPacketConfirmTransaction) event.getPacket());
            return;
        }

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            handleEntityAction((C0BPacketEntityAction) event.getPacket());
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            handleAttack((C02PacketUseEntity) event.getPacket(), event);
        }
    }
    
    private void handleTransaction(C0FPacketConfirmTransaction packet) {
        this.lastTransactionTime = System.currentTimeMillis();
        this.transactionCounter++;
        this.awaitingTransaction = false;
        
        if (acTarget.getValue() == 1 || acTarget.getValue() == 0) {
            forceNextHit = true;
        }
    }
    
    private void handleEntityAction(C0BPacketEntityAction packet) {
        switch (packet.getAction()) {
            case START_SPRINTING:
                this.sprintState = true;
                break;
            case STOP_SPRINTING:
                this.sprintState = false;
                break;
        }
    }
    
    private void handleAttack(C02PacketUseEntity use, PacketEvent event) {
        if (use.getAction() != C02PacketUseEntity.Action.ATTACK) return;

        if (mc.theWorld == null) return;
        
        Entity target = use.getEntityFromWorld(mc.theWorld);
        if (target == null || target instanceof EntityLargeFireball) return;
        if (!(target instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) target;
        
        if (!checkCPS()) {
            allowedHits++;
            return;
        }
        
        if (consecutiveBlocks >= maxConsecutiveBlocks || forceNextHit) {
            forceNextHit = false;
            consecutiveBlocks = 0;
            allowedHits++;
            recordClick();
            return;
        }

        boolean allow = determineHitAllow(living);
        
        if (!allow && humanization.getValue() && RANDOM.nextDouble() < randomization.getValue()) {
            allow = true;
        }

        if (!allow) {
            event.setCancelled(true);
            blockedHits++;
            consecutiveBlocks++;
            
            fixMotionUniversal();
            
            if (RANDOM.nextInt(6) == 0) {
                sendTransactionPacket();
            }
        } else {
            allowedHits++;
            consecutiveBlocks = 0;
            recordClick();
            
            if (mode.getValue() == 3) {
                manageGhostMode();
            }
        }
    }
    
    private boolean checkCPS() {
        long now = System.currentTimeMillis();
        clickTimestamps.removeIf(time -> now - time > 1000);
        
        // FIX: Use intValue() for IntegerProperty comparison
        if (clickTimestamps.size() >= maxCps.getValue().intValue()) {
            return false;
        }
        return true;
    }
    
    private void recordClick() {
        long now = System.currentTimeMillis();
        clickTimestamps.add(now);
        
        if (lastClickTime > 0) {
            double interval = now - lastClickTime;
            double instantCps = 1000.0 / interval;
            avgCps = (avgCps * 0.7) + (instantCps * 0.3);
        }
        lastClickTime = now;
    }
    
    private boolean determineHitAllow(EntityLivingBase target) {
        switch (mode.getValue()) {
            case 0:
                return prioritizeSmart(mc.thePlayer, target);
            case 1:
                return prioritizeAggressive(mc.thePlayer, target);
            case 2:
                return prioritizeDefensive(mc.thePlayer, target);
            case 3:
                return prioritizeGhost(mc.thePlayer, target);
            default:
                return true;
        }
    }
    
    private boolean prioritizeSmart(EntityLivingBase player, EntityLivingBase target) {
        double healthPercent = player.getHealth() / player.getMaxHealth();
        double targetHealthPercent = target.getHealth() / target.getMaxHealth();
        
        if (healthPercent < 0.3) {
            return prioritizeDefensive(player, target);
        }
        
        if (targetHealthPercent < 0.3) {
            return prioritizeAggressive(player, target);
        }
        
        return prioritizeSecondHit(player, target);
    }
    
    private boolean prioritizeAggressive(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0) return true;
        
        double dist = player.getDistanceToEntity(target);
        if (dist < dynamicDistanceThreshold * 1.2) return true;
        
        if (!isMovingTowards(player, target, dynamicAngleThreshold * 0.8)) {
            return true;
        }
        
        fixMotionUniversal();
        return false;
    }
    
    private boolean prioritizeDefensive(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0) return true;
        
        if (player.hurtTime <= player.maxHurtTime - dynamicHurtBuffer) return true;
        
        double dist = player.getDistanceToEntity(target);
        if (dist < dynamicDistanceThreshold * 0.8) return true;
        
        if (!isMovingTowards(target, player, dynamicAngleThreshold)) return true;
        if (!isMovingTowards(player, target, dynamicAngleThreshold)) return true;
        
        if (player.isSwingInProgress && RANDOM.nextInt(3) != 0) {
            return true;
        }
        
        fixMotionUniversal();
        return false;
    }
    
    private boolean prioritizeGhost(EntityLivingBase player, EntityLivingBase target) {
        long now = System.currentTimeMillis();
        
        if (now - lastGhostSwitch > 3000 + RANDOM.nextInt(2000)) {
            ghostPattern = 3 + RANDOM.nextInt(4);
            lastGhostSwitch = now;
            ghostHitsRemaining = ghostPattern;
        }
        
        if (ghostHitsRemaining > 0) {
            ghostHitsRemaining--;
            return true;
        }
        
        if (RANDOM.nextInt(ghostPattern + 2) == 0) {
            ghostHitsRemaining = ghostPattern;
            fixMotionUniversal();
            return false;
        }
        
        return prioritizeSecondHit(player, target);
    }

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0) return true;
        if (player.hurtTime <= player.maxHurtTime - dynamicHurtBuffer) return true;
        
        double dist = player.getDistanceToEntity(target);
        if (dist < dynamicDistanceThreshold) return true;
        
        if (!isMovingTowards(target, player, dynamicAngleThreshold)) return true;
        if (!isMovingTowards(player, target, dynamicAngleThreshold)) return true;
        
        if (rotationCheck.getValue() && !checkRotationLegitimacy(target)) {
            return true;
        }
        
        fixMotionUniversal();
        return false;
    }
    
    private boolean checkRotationLegitimacy(EntityLivingBase target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        double dy = (target.posY + target.getEyeHeight()) - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        
        float expectedYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float expectedPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz)));
        
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(expectedYaw - currentYaw));
        float pitchDiff = Math.abs(expectedPitch - currentPitch);
        
        if (yawDiff < 2 && pitchDiff < 2) {
            targetYaw = currentYaw + (RANDOM.nextFloat() - 0.5f) * 4;
            targetPitch = currentPitch + (RANDOM.nextFloat() - 0.5f) * 2;
            rotationTicks = 2 + RANDOM.nextInt(2);
            return false;
        }
        
        return true;
    }

    private void fixMotionUniversal() {
        if (this.set) return;

        try {
            this.preservedMotionX = mc.thePlayer.motionX;
            this.preservedMotionZ = mc.thePlayer.motionZ;
            
            double preserveFactor = 0.55 + (RANDOM.nextDouble() * 0.25);
            
            switch (acTarget.getValue()) {
                case 1:
                    preserveFactor = 0.8 + (RANDOM.nextDouble() * 0.15);
                    break;
                case 2:
                    preserveFactor = 0.6 + (RANDOM.nextDouble() * 0.2);
                    break;
                case 3:
                    preserveFactor = 0.9;
                    break;
                case 4:
                    preserveFactor = (blockedHits % 3 == 0) ? 0.7 : 0.85;
                    break;
            }
            
            mc.thePlayer.motionX *= preserveFactor;
            mc.thePlayer.motionZ *= preserveFactor;
            this.motionPreserveTicks = 3;
            this.set = true;
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!this.set) return;
        this.set = false;
    }
    
    private void manageGhostMode() {
        if (RANDOM.nextInt(10) == 0) {
            ghostPattern = Math.max(2, ghostPattern + (RANDOM.nextBoolean() ? 1 : -1));
        }
    }
    
    private void sendTransactionPacket() {
        if (mc.thePlayer != null && mc.getNetHandler() != null) {
            short actionNumber = (short) (transactionId++ % 32767);
            mc.getNetHandler().addToSendQueue(new C0FPacketConfirmTransaction(0, actionNumber, true));
        }
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        if (movementLength < 0.001) return false;

        mx /= movementLength;
        mz /= movementLength;

        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        if (targetLength < 0.001) return false;

        tx /= targetLength;
        tz /= targetLength;

        double dotProduct = mx * tx + mz * tz;
        
        double variation = Math.toRadians(RANDOM.nextDouble() * 3.0);
        return dotProduct >= Math.cos(Math.toRadians(maxAngle) + variation);
    }

    @Override
    public void onDisabled() {
        this.resetMotion();
        this.sprintState = false;
        this.set = false;
        this.savedSlowdown = 0.0;
        this.blockedHits = 0;
        this.allowedHits = 0;
        this.consecutiveBlocks = 0;
        this.hitCounter = 0;
        this.forceNextHit = false;
        this.clickTimestamps.clear();
        this.rotationTicks = 0;
        this.motionPreserveTicks = 0;
        if (this.velocityHistory != null) this.velocityHistory.clear();
    }
    
    @Override
    public void onEnabled() {
        initializeDynamicValues();
        this.clickTimestamps = new ArrayList<>();
        this.velocityHistory = new ArrayDeque<>();
        this.lastClickTime = 0;
        this.avgCps = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString(), this.acTarget.getModeString()};
    }
}
