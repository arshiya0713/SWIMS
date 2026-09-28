package com.swims.app.ml.bandit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BanditTest {

    @Test
    fun testContextBuckets() {
        assertEquals(0, BanditContext.hourBucketOf(6))
        assertEquals(1, BanditContext.hourBucketOf(9))
        assertEquals(2, BanditContext.hourBucketOf(12))
        assertEquals(5, BanditContext.hourBucketOf(22))

        assertEquals(0, BanditContext.deficitLevelOf(1000, 1000.0))
        assertEquals(1, BanditContext.deficitLevelOf(700, 1000.0))
        assertEquals(2, BanditContext.deficitLevelOf(200, 1000.0))
    }

    @Test
    fun testRewardShaping() {
        assertEquals(1.0, Reward.of(BanditArm.SKIP, true), 0.0001)
        assertEquals(0.85, Reward.of(BanditArm.GENTLE, true), 0.0001)
        assertEquals(0.13, Reward.of(BanditArm.SKIP, false), 0.0001)
        assertEquals(0.0, Reward.of(BanditArm.MOTIVATIONAL, false), 0.0001)
    }

    @Test
    fun testThompsonPolicyWarmup() {
        val policy = ThompsonSamplingPolicy(Random(42))
        val posteriors = listOf(
            ArmPosterior("test", BanditArm.SKIP, 1.0, 1.0),
            ArmPosterior("test", BanditArm.GENTLE, 1.0, 1.0),
            ArmPosterior("test", BanditArm.MOTIVATIONAL, 1.0, 1.0)
        )

        val selected = policy.selectArm(posteriors)
        assertTrue(selected in BanditArm.entries)
    }

    @Test
    fun testThompsonPolicyExploitation() {
        val policy = ThompsonSamplingPolicy(Random(42))
        val posteriors = listOf(
            ArmPosterior("test", BanditArm.SKIP, 1.0, 100.0),
            ArmPosterior("test", BanditArm.GENTLE, 100.0, 1.0),
            ArmPosterior("test", BanditArm.MOTIVATIONAL, 1.0, 100.0)
        )

        assertEquals(BanditArm.GENTLE, policy.selectArm(posteriors))
    }
}
