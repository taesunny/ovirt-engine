package org.ovirt.engine.core.vdsbroker.builder.vminfo;

import javax.inject.Singleton;

import org.ovirt.engine.core.common.businessentities.VmDevice;
import org.ovirt.engine.core.common.businessentities.network.VmInterfaceType;
import org.ovirt.engine.core.common.businessentities.network.VmNic;
import org.ovirt.engine.core.common.utils.VmDeviceCommonUtils;

@Singleton
public class MultiQueueUtils {

    private static final int OPTIMAL_NUM_OF_QUEUES_PER_VNIC = 4;

    public boolean isInterfaceQueuable(VmDevice vmDevice, VmNic vmNic) {
        return vmDevice.isPlugged() && VmDeviceCommonUtils.isBridge(vmDevice) && vmNic.getType() != null
                && VmInterfaceType.forValue(vmNic.getType()) == VmInterfaceType.pv;
    }

    public int getOptimalNumOfQueuesPerVnic(int numOfCpus) {
        return Math.min(numOfCpus, OPTIMAL_NUM_OF_QUEUES_PER_VNIC);
    }
}