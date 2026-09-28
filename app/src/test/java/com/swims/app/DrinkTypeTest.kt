package com.swims.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DrinkTypeTest {

    @Test
    fun testWater() {
        assertEquals(200, DrinkType.WATER.hydration(200))
    }

    @Test
    fun testTea() {
        assertEquals(190, DrinkType.TEA.hydration(200))
    }

    @Test
    fun testCoffee() {
        // 90%
        assertEquals(180, DrinkType.COFFEE.hydration(200))
    }

    @Test
    fun testJuice() {
        // 85%
        assertEquals(170, DrinkType.JUICE.hydration(200))
    }

    @Test
    fun testMilk() {
        // 90%
        assertEquals(180, DrinkType.MILK.hydration(200))
    }
}
