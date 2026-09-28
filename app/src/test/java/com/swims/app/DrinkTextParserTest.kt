package com.swims.app.ml.nlp

import com.swims.app.data.model.DrinkType
import org.junit.Assert.assertEquals
import org.junit.Test

class DrinkTextParserTest {

    @Test
    fun testValidInput() {
        val result = DrinkTextParser.parse("250ml water")
        assertEquals(1, result.drinks.size)
        assertEquals(250, result.drinks[0].amountMl)
        assertEquals(DrinkType.WATER, result.drinks[0].type)
    }

    @Test
    fun testDifferentUnits() {
        val r1 = DrinkTextParser.parse("1.5 L water")
        assertEquals(1500, r1.drinks[0].amountMl)
        
        val r2 = DrinkTextParser.parse("8 oz water")
        assertEquals((8 * 29.57).toInt(), r2.drinks[0].amountMl)
    }

    @Test
    fun testDrinkTypes() {
        assertEquals(DrinkType.COFFEE, DrinkTextParser.parse("200ml coffee").drinks[0].type)
        assertEquals(DrinkType.TEA, DrinkTextParser.parse("200ml tea").drinks[0].type)
        assertEquals(DrinkType.JUICE, DrinkTextParser.parse("200ml juice").drinks[0].type)
        assertEquals(DrinkType.MILK, DrinkTextParser.parse("200ml milk").drinks[0].type)
    }

    @Test
    fun testInvalidInput() {
        val result = DrinkTextParser.parse("hello world")
        assertEquals(0, result.drinks.size)
        assertEquals(true, result.isEmpty)
    }

    @Test
    fun testAmbiguousInputAndContainers() {
        val r1 = DrinkTextParser.parse("a glass of water")
        assertEquals(250, r1.drinks[0].amountMl)
        
        val r2 = DrinkTextParser.parse("a large coffee")
        assertEquals(340, r2.drinks[0].amountMl) 
    }
}
