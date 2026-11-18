package cn.zhixing.facephoto.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SdkErrorMessageTest {
    @Test
    void distinguishesMissingExpiredAndUsedLicenses() {
        assertTrue(MainController.batchError("SDK_INIT_FAILED (-1015)").contains("app/worker/sdk/license"));
        assertTrue(MainController.batchError("SDK_INIT_FAILED (-1018)").contains("已过期"));
        assertTrue(MainController.batchError("SDK_INIT_FAILED (-1019)").contains("设备绑定"));
        assertTrue(MainController.batchError("SDK_INIT_FAILED (-1022)").contains("-1022"));
    }
}
