package dev.ryvrook.trackerapi.facts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CropsTest {

    @Test
    void aGrowthStageMapsToItsCropBlock() {
        assertEquals("Plant_Crop_Carrot_Block", Crops.baseId("*Plant_Crop_Carrot_Block_State_Definitions_StageFinal"));
        assertEquals("Plant_Crop_Wheat_Block", Crops.baseId("*Plant_Crop_Wheat_Block_State_Definitions_Stage2"));
        assertEquals("Plant_Crop_Berry_Block", Crops.baseId("Plant_Crop_Berry_Block_State_Definitions_Harvested"));
    }

    @Test
    void aPlainIdStaysAsItIs() {
        assertEquals("Plant_Crop_Carrot_Block", Crops.baseId("Plant_Crop_Carrot_Block"));
        assertEquals("Plant_Sapling_Oak", Crops.baseId("*Plant_Sapling_Oak"));
        assertEquals("Rock_Stone", Crops.baseId("Rock_Stone"));
        assertNull(Crops.baseId(null));
    }
}
