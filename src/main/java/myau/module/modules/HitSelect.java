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
import myau.property.properties.NumberProperty;
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
    // FIX: Use instance Random instead of ThreadLocalRandom for consistency
    private static final Random RANDOM = new Random();

    // Universal bypass modes
    public final ModeProperty mode = new ModeProperty("Mode", 0, new String[]{"SMART", "AGGRESSIVE", "DEFENSIVE", "GHOST"});
    public final ModeProperty acTarget = new ModeProperty("AC_Target", 0, new String[]{"AUTO", "GRIM", "VULCAN", "VERUS", "AAC", "POLAR", "NCP"});
    public final BooleanProperty humanization = new BooleanProperty("Humanization", true);
    public final BooleanProperty rotationCheck = new BooleanProperty("RotationCheck", true);
    public final NumberProperty randomization = new NumberProperty("Randomization", 0.15, 0.0, 0.3, 0.01);
    public final NumberProperty maxCps = new NumberProperty("MaxCPS", 12, 8, 20, 1);

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
    
    // Transaction tracking (Grim/Polar) - FIX: Added missing field
    private int transactionId = 0;
    private long lastTransactionTime = 0;
    private int transactionCounter = 0;
    private boolean awaitingTransaction = false; // FIX: Declared missing field
    
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
    
    // NEW: Motion preservation system (backward compatible)
    private double preservedMotionX = 0;
    private double preservedMotionZ = 0;
    private int motionPreserveTicks = 0;
    
    // NEW: Velocity tracking for modern ACs (backward compatible)
    private Deque<Double> velocityHistory = new ArrayDeque<>(10);
    private double lastVelocity = 0;
    
    // NEW: CPS variance tracking (backward compatible)
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
            updateMotionPreservation(); // NEW: Maintains motion state
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
        
        // Smooth rotation updates to bypass AAC/Matrix rotation checks
        currentYaw = mc.thePlayer.rotationYaw;
        currentPitch = mc.thePlayer.rotationPitch;
        
        if (rotationTicks > 0) {
            float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
            float pitchDiff = targetPitch - currentPitch;
            
            // Gradual rotation (bypasses snap checks)
            float yawStep = yawDiff * 0.3f;
            float pitchStep = pitchDiff * 0.3f;
            
            // Add micro-variation (bypasses constant rotation checks)
            yawStep += (RANDOM.nextFloat() - 0.5f) * 0.5f;
            pitchStep += (RANDOM.nextFloat() - 0.5f) * 0.5f;
            
            mc.thePlayer.rotationYaw += yawStep;
            mc.thePlayer.rotationPitch += pitchStep;
            rotationTicks--;
        }
    }
    
    private void updateACDetection() {
        // Auto-detect AC type based on server response patterns
        if (acTarget.getValue() != 0) return; // Manual override
        
        // Detect Grim by transaction patterns
        if (transactionCounter > 10 && System.currentTimeMillis() - lastTransactionTime < 100) {
            // Likely Grim or Polar
            randomizationFactor = 0.75; // Stricter randomization
        }
    }
    
    // NEW: Motion preservation system (backward compatible addition)
    private void updateMotionPreservation() {
        if (motionPreserveTicks > 0) {
            motionPreserveTicks--;
            // Gradual restoration instead of instant (bypasses "snap" detection)
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
        
        // Vary max consecutive blocks
        this.maxConsecutiveBlocks = 2 + RANDOM.nextInt(3);
    }
    
    private void cleanOldClicks() {
        long now = System.currentTimeMillis();
        clickTimestamps.removeIf(time -> now - time > 1000);
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) return;

        // Universal transaction handling (Grim/Polar/Vulcan)
        if (event.getPacket() instanceof C0FPacketConfirmTransaction) {
            handleTransaction((C0FPacketConfirmTransaction) event.getPacket());
            return;
        }

        // Sprint state tracking (All ACs)
        if (event.getPacket() instanceof C0BPacketEntityAction) {
            handleEntityAction((C0BPacketEntityAction) event.getPacket());
            return;
        }

        // Main attack logic
        if (event.getPacket() instanceof C02PacketUseEntity) {
            handleAttack((C02PacketUseEntity) event.getPacket(), event);
        }
    }
    
    private void handleTransaction(C0FPacketConfirmTransaction packet) {
        this.lastTransactionTime = System.currentTimeMillis();
        this.transactionCounter++;
        this.awaitingTransaction = false;
        
        // Grim/Vulcan: Don't block hits around transactions
        if (acTarget.getValue() == 1 || acTarget.getValue() == 0) { // Grim or Auto
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

        // FIX: Added null check for mc.theWorld
        if (mc.theWorld == null) return;
        
        Entity target = use.getEntityFromWorld(mc.theWorld);
        if (target == null || target instanceof EntityLargeFireball) return;
        if (!(target instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) target;
        
        // Universal CPS check (AAC/Vulcan/Matrix)
        if (!checkCPS()) {
            allowedHits++;
            return; // Allow hit to maintain CPS
        }
        
        // Force allow after consecutive blocks (Universal)
        if (consecutiveBlocks >= maxConsecutiveBlocks || forceNextHit) {
            forceNextHit = false;
            consecutiveBlocks = 0;
            allowedHits++;
            recordClick();
            return;
        }

        boolean allow = determineHitAllow(living);
        
        // Apply humanization randomization
        if (!allow && humanization.getValue() && RANDOM.nextDouble() < randomization.getValue()) {
            allow = true;
        }

        if (!allow) {
            event.setCancelled(true);
            blockedHits++;
            consecutiveBlocks++;
            
            // Universal evasion: Vary motion fix method
            fixMotionUniversal();
            
            // Occasional transaction sync (Polar/Grim)
            if (RANDOM.nextInt(6) == 0) {
                sendTransactionPacket();
            }
        } else {
            allowedHits++;
            consecutiveBlocks = 0;
            recordClick();
            
            // Ghost mode pattern management
            if (mode.getValue() == 3) {
                manageGhostMode();
            }
        }
    }
    
    private boolean checkCPS() {
        long now = System.currentTimeMillis();
        clickTimestamps.removeIf(time -> now - time > 1000);
        
        if (clickTimestamps.size() >= maxCps.getValue()) {
            return false; // Too many clicks, allow this one
        }
        return true;
    }
    
    private void recordClick() {
        long now = System.currentTimeMillis();
        clickTimestamps.add(now);
        
        // NEW: Track CPS variance for humanization (backward compatible)
        if (lastClickTime > 0) {
            double interval = now - lastClickTime;
            double instantCps = 1000.0 / interval;
            avgCps = (avgCps * 0.7) + (instantCps * 0.3); // Exponential moving average
        }
        lastClickTime = now;
    }
    
    private boolean determineHitAllow(EntityLivingBase target) {
        switch (mode.getValue()) {
            case 0: // SMART - Context aware
                return prioritizeSmart(mc.thePlayer, target);
            case 1: // AGGRESSIVE - Prioritize damage
                return prioritizeAggressive(mc.thePlayer, target);
            case 2: // DEFENSIVE - Prioritize safety
                return prioritizeDefensive(mc.thePlayer, target);
            case 3: // GHOST - Human-like patterns
                return prioritizeGhost(mc.thePlayer, target);
            default:
                return true;
        }
    }
    
    private boolean prioritizeSmart(EntityLivingBase player, EntityLivingBase target) {
        // Dynamic decision based on health and situation
        double healthPercent = player.getHealth() / player.getMaxHealth();
        double targetHealthPercent = target.getHealth() / target.getMaxHealth();
        
        // Low health = defensive, prioritize survival
        if (healthPercent < 0.3) {
            return prioritizeDefensive(player, target);
        }
        
        // Target low health = aggressive finish
        if (targetHealthPercent < 0.3) {
            return prioritizeAggressive(player, target);
        }
        
        // Default to second-hit logic with variation
        return prioritizeSecondHit(player, target);
    }
    
    private boolean prioritizeAggressive(EntityLivingBase player, EntityLivingBase target) {
        // Allow more hits, block only obvious bad hits
        if (target.hurtTime != 0) return true;
        
        double dist = player.getDistanceToEntity(target);
        if (dist < dynamicDistanceThreshold * 1.2) return true;
        
        // Block only if moving away and far
        if (!isMovingTowards(player, target, dynamicAngleThreshold * 0.8)) {
            return true;
        }
        
        fixMotionUniversal();
        return false;
    }
    
    private boolean prioritizeDefensive(EntityLivingBase player, EntityLivingBase target) {
        // Conservative: Only hit when optimal
        if (target.hurtTime != 0) return true;
        
        if (player.hurtTime <= player.maxHurtTime - dynamicHurtBuffer) return true;
        
        double dist = player.getDistanceToEntity(target);
        if (dist < dynamicDistanceThreshold * 0.8) return true;
        
        if (!isMovingTowards(target, player, dynamicAngleThreshold)) return true;
        if (!isMovingTowards(player, target, dynamicAngleThreshold)) return true;
        
        // Additional check: Don't hit if player is swinging (AAC/Vulcan check)
        if (player.isSwingInProgress && RANDOM.nextInt(3) != 0) {
            return true;
        }
        
        fixMotionUniversal();
        return false;
    }
    
    private boolean prioritizeGhost(EntityLivingBase player, EntityLivingBase target) {
        // Human-like decision making with patterns
        long now = System.currentTimeMillis();
        
        // Switch patterns periodically
        if (now - lastGhostSwitch > 3000 + RANDOM.nextInt(2000)) {
            ghostPattern = 3 + RANDOM.nextInt(4);
            lastGhostSwitch = now;
            ghostHitsRemaining = ghostPattern;
        }
        
        // Pattern-based allowing
        if (ghostHitsRemaining > 0) {
            ghostHitsRemaining--;
            return true;
        }
        
        // Occasional "miss" or block
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
        
        // AAC/Vulcan: Check for rotation consistency
        if (rotationCheck.getValue() && !checkRotationLegitimacy(target)) {
            return true; // Allow if rotation suspicious to avoid flag
        }
        
        fixMotionUniversal();
        return false;
    }
    
    private boolean checkRotationLegitimacy(EntityLivingBase target) {
        // Calculate expected rotation to target
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        double dy = (target.posY + target.getEyeHeight()) - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        
        float expectedYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float expectedPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz)));
        
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(expectedYaw - currentYaw));
        float pitchDiff = Math.abs(expectedPitch - currentPitch);
        
        // If looking too perfectly, add variance (bypasses AAC heuristic)
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
            // FIX: Proper motion preservation system
            this.preservedMotionX = mc.thePlayer.motionX;
            this.preservedMotionZ = mc.thePlayer.motionZ;
            
            // Method 1: Direct motion manipulation (bypasses module-based detection)
            double preserveFactor = 0.55 + (RANDOM.nextDouble() * 0.25);
            
            // Vary method based on AC target
            switch (acTarget.getValue()) {
                case 1: // Grim - Use minimal motion change
                    preserveFactor = 0.8 + (RANDOM.nextDouble() * 0.15);
                    break;
                case 2: // Vulcan - Standard preservation
                    preserveFactor = 0.6 + (RANDOM.nextDouble() * 0.2);
                    break;
                case 3: // Verus - Aggressive preservation
                    preserveFactor = 0.9;
                    break;
                case 4: // AAC - Variable based on pattern
                    preserveFactor = (blockedHits % 3 == 0) ? 0.7 : 0.85;
                    break;
            }
            
            mc.thePlayer.motionX *= preserveFactor;
            mc.thePlayer.motionZ *= preserveFactor;
            this.motionPreserveTicks = 3; // NEW: Enable gradual restoration
            this.set = true;
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!this.set) return;

        try {
            // FIX: Motion is now handled by updateMotionPreservation() for gradual reset
            this.set = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void manageGhostMode() {
        // Vary ghost pattern to avoid detection
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
        
        // Add micro-variation to angle check (bypasses precise angle detection)
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
        this.motionPreserveTicks = 0; // NEW: Reset motion preservation
        this.velocityHistory.clear(); // NEW: Clear velocity history
    }
    
    @Override
    public void onEnabled() {
        initializeDynamicValues();
        this.clickTimestamps = new ArrayList<>();
        this.velocityHistory = new ArrayDeque<>(); // NEW: Initialize velocity tracking
        this.lastClickTime = 0;
        this.avgCps = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString(), this.acTarget.getModeString()};
    }
}
