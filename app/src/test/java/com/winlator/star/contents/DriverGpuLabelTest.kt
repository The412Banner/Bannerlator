package com.winlator.star.contents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverGpuLabelTest {
    @Test fun bannersTurnipVariants() {
        assertEquals("Adreno 6xx/7xx", DriverGpuLabel.of("Turnip-v27.0.0-20261105-r3"))
        assertEquals("Adreno 6xx/7xx", DriverGpuLabel.of("Turnip-v27.0.0-20261104-Linux"))
        assertEquals("Adreno 6xx/7xx", DriverGpuLabel.of("Turnip-v27.0.0-20261104-r2.zip"))
        assertEquals("Adreno 8xx", DriverGpuLabel.of("Turnip-v27.0.0-20261105-r2-A8xx"))
        assertEquals("Adreno 710/720/722", DriverGpuLabel.of("Turnip-v27.0.0-20261105-r2-710-720-Test-Linux"))
        assertEquals("Adreno 740 One UI", DriverGpuLabel.of("Turnip-v27.0.0-20261105-r2-8G2-OneUI"))
        assertEquals("All Adreno", DriverGpuLabel.of("Turnip-v27.0.0-20261105-r3-Banners-AIO-Linux"))
        assertEquals("Adreno 722", DriverGpuLabel.of("Turnip-a722-pure-pre1-A722"))
    }

    @Test fun otherSourcesOnlyWhenTheNameSays() {
        assertEquals("Adreno 840", DriverGpuLabel.of("Turnip_MTR_v1.8.3_A840P_Test"))
        assertEquals("Adreno 8xx", DriverGpuLabel.of("Turnip_MTR_v1.8_A8XX_RC3"))
        assertEquals("Adreno 735", DriverGpuLabel.of("turnip_v24.2.0_partial_a735_support_TEST.zip"))
        assertEquals("Adreno 6xx", DriverGpuLabel.of("Turnip-24.1.0.adpkg_R18.a6xx.zip"))
        assertEquals("Adreno 7xx", DriverGpuLabel.of("MTd.experimental.Unsupported.a7xx.zip"))
        assertEquals("Adreno 8xx", DriverGpuLabel.of("Turnip_Gen8_V38"))
        assertEquals("Adreno 8xx", DriverGpuLabel.of("8Elite2-842.6"))
        assertEquals("Adreno 750", DriverGpuLabel.of("v24.2.0_a32_patch", "turnip-24.1.0.adpkg_a750.zip"))
        assertNull(DriverGpuLabel.of("Turnip_MTR_v1.8.8"))
        assertNull(DriverGpuLabel.of("mainline-turnip-V32.zip"))
        assertNull(DriverGpuLabel.of("Turnip_v27.0.0-R1-Oneui"))
        assertNull(DriverGpuLabel.of(""))
    }

    @Test fun driverVersionsAndHashesAreNotGpus() {
        assertNull(DriverGpuLabel.of("adreno744.12.adpkg.zip"))
        assertNull(DriverGpuLabel.of("adreno-676.41-adpkg.zip"))
        assertNull(DriverGpuLabel.of("Qualcomm_840_adpkg.zip"))
        assertNull(DriverGpuLabel.of("Turnip-PixelyIon-tu-newat-430a593.zip"))
        assertNull(DriverGpuLabel.of("Turnip-24.2.0_Adreno_32_patch.zip"))
    }
}
