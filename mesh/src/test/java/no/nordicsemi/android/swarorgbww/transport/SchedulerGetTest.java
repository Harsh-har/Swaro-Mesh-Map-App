package no.nordicsemi.android.swarorgbww.transport;

import org.junit.Test;

import no.nordicsemi.android.swarorgbww.ApplicationKey;
import no.nordicsemi.android.swarorgbww.utils.MeshParserUtils;

import static org.junit.Assert.assertNull;

public class SchedulerGetTest {

    final ApplicationKey applicationKey = new ApplicationKey(MeshParserUtils.hexToInt("0456"), MeshParserUtils.toByteArray("63964771734fbd76e3b40519d1d94a48"));

    @Test
    public void test_reading_data() {

        SchedulerGet schedulerGet = new SchedulerGet(applicationKey);

        assertNull(schedulerGet.mParameters);
    }
}
