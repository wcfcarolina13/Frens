package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotArrowRecoveryPolicyTest {

    @Test
    void rejectsOrdinaryArrowThatSurvivalCannotPickUp() {
        assertFalse(BotArrowRecoveryService.shouldTrackProjectile(
                false,
                true,
                PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY,
                false
        ));
    }

    @Test
    void rejectsOrdinaryArrowOwnedBySomeoneElse() {
        assertFalse(BotArrowRecoveryService.shouldTrackProjectile(
                false,
                false,
                PersistentProjectileEntity.PickupPermission.ALLOWED,
                false
        ));
    }

    @Test
    void rejectsOrdinaryArrowTouchingWater() {
        assertFalse(BotArrowRecoveryService.shouldTrackProjectile(
                false,
                true,
                PersistentProjectileEntity.PickupPermission.ALLOWED,
                true
        ));
    }

    @Test
    void acceptsDryBotOwnedSurvivalArrow() {
        assertTrue(BotArrowRecoveryService.shouldTrackProjectile(
                false,
                true,
                PersistentProjectileEntity.PickupPermission.ALLOWED,
                false
        ));
    }

    @Test
    void acceptsBotOwnedTridentUnderwater() {
        assertTrue(BotArrowRecoveryService.shouldTrackProjectile(
                true,
                true,
                PersistentProjectileEntity.PickupPermission.DISALLOWED,
                true
        ));
    }

    @Test
    void rejectsForeignTrident() {
        assertFalse(BotArrowRecoveryService.shouldTrackProjectile(
                true,
                false,
                PersistentProjectileEntity.PickupPermission.ALLOWED,
                false
        ));
    }
}
