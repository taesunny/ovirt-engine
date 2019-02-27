package org.ovirt.engine.core.bll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.hostedengine.HostedEngineHelper;
import org.ovirt.engine.core.bll.job.ExecutionContext;
import org.ovirt.engine.core.bll.job.ExecutionHandler;
import org.ovirt.engine.core.bll.scheduling.SchedulingManager;
import org.ovirt.engine.core.bll.scheduling.SchedulingParameters;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.MaintenanceVdsParameters;
import org.ovirt.engine.core.common.action.MigrateMultipleVmsParameters;
import org.ovirt.engine.core.common.businessentities.HaMaintenanceMode;
import org.ovirt.engine.core.common.businessentities.MigrationSupport;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.comparators.VmsComparer;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.job.Step;
import org.ovirt.engine.core.common.job.StepEnum;
import org.ovirt.engine.core.common.vdscommands.SetHaMaintenanceModeVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.MessageBundler;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.di.Injector;

@NonTransactiveCommandAttribute
public class MaintenanceVdsCommand<T extends MaintenanceVdsParameters> extends VdsCommand<T> {

    private List<VM> vms;
    private boolean haMaintenanceFailed;
    private List<String> nonMigratableVms = new ArrayList<>();
    @Inject
    private SchedulingManager schedulingManager;
    @Inject
    private VdsDao vdsDao;
    @Inject
    private VmDao vmDao;

    public MaintenanceVdsCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
        haMaintenanceFailed = false;
    }

    @Override
    protected void executeCommand() {
        if (getVds().getStatus() == VDSStatus.Maintenance) {
            // nothing to do
            setSucceeded(true);
        } else {
            orderListOfRunningVmsOnVds(getVdsId());

            if (getVds().getHighlyAvailableIsConfigured()) {
                SetHaMaintenanceModeVDSCommandParameters params
                        = new SetHaMaintenanceModeVDSCommandParameters(getVds(), HaMaintenanceMode.LOCAL, true);
                if (!runVdsCommand(VDSCommandType.SetHaMaintenanceMode, params).getSucceeded()) {
                    haMaintenanceFailed = true;
                    // HA maintenance failure is fatal only if the Hosted Engine vm is running on this host
                    if (isHostedEngineOnVds()) {
                        setSucceeded(false);
                        return;
                    }
                }
            }

            setSucceeded(migrateAllVms(getExecutionContext()));

            // if non responsive move directly to maintenance
            if (getVds().getStatus() == VDSStatus.NonResponsive
                    || getVds().getStatus() == VDSStatus.Connecting
                    || getVds().getStatus() == VDSStatus.Down) {
                setVdsStatus(VDSStatus.Maintenance);
            }
        }
        // if there's VM(s) in this VDS which is migrating, mark this command as async
        // as the migration(s) is a step of this job, so this job must not be cleaned yet
        if (isVmsExist()) {
            ExecutionHandler.setAsyncJob(getExecutionContext(), true);
        }
    }

    protected boolean isVmsExist() {
        return vms != null && !vms.isEmpty();
    }

    protected void orderListOfRunningVmsOnVds(Guid vdsId) {
        vms = vmDao.getAllRunningForVds(vdsId);
        if (Config.<Boolean>getValue(ConfigValues.MaintenanceVdsIgnoreExternalVms)) {
            vms = vms.stream().filter(vm -> !vm.isExternalVm()).collect(Collectors.toList());
        }
        vms.sort(new VmsComparer().reversed());
    }

    /**
     * Note: you must call {@link #orderListOfRunningVmsOnVds(Guid)} before calling this method
     */
    protected boolean migrateAllVms(ExecutionContext parentContext) {
        return migrateAllVms(parentContext, false);
    }

    private boolean canScheduleVm(VM vm) {
        List<Guid> blacklist = new ArrayList<>();
        if (getVdsId() != null) {
            blacklist.add(getVdsId());
        }
        return !schedulingManager.canSchedule(
                getCluster(),
                vm,
                blacklist, //blacklist only contains the host we're putting to maintenance
                Collections.emptyList(), //no whitelist
                new SchedulingParameters(),
                new ArrayList<>()
        ).isEmpty();
    }
    /**
     * Note: you must call {@link #orderListOfRunningVmsOnVds(Guid)} before calling this method
     */
    protected boolean migrateAllVms(ExecutionContext parentContext, boolean HAOnly) {

        boolean succeeded = true;

        List<VM> vmsToMigrate = new ArrayList<>();
        for (VM vm : vms) {
            if (vm.isHostedEngine()) {
                // check if there is host which can be used for HE
                if (!canScheduleVm(vm)) {
                    succeeded = false;
                    log.error("There is no host capable of running the hosted engine VM");
                }
                // The Hosted Engine vm is migrated by the HA agent
                continue;
            }
            // if HAOnly is true check that vm is HA (auto_startup should be true)
            if (vm.getStatus() != VMStatus.MigratingFrom && (!HAOnly || vm.isAutoStartup())) {
                vmsToMigrate.add(vm);
            }
        }

        if (!migrateVms(vmsToMigrate, parentContext)) {
            succeeded = false;
            // There is no way to find out which VMs failed to migrate, so the error message is general.
            log.error("Failed to migrate one or more VMs.");
        }
        return succeeded;
    }

    private boolean migrateVms(List<VM> vms, ExecutionContext parentContext) {
        boolean forceMigration = !getParameters().isInternal();

        MigrateMultipleVmsParameters parameters = new MigrateMultipleVmsParameters(
                vms.stream().map(VM::getId).collect(Collectors.toList()),
                forceMigration
        );
        parameters.setHostBlackList(Collections.singletonList(getVdsId()));

        boolean canIgnoreVmAffinity = vms.stream()
                .anyMatch(vm -> Config.<Boolean>getValue(
                        ConfigValues.IgnoreVmToVmAffinityForHostMaintenance,
                        vm.getCompatibilityVersion().getValue()
                ));

        if (canIgnoreVmAffinity) {
            parameters.setCanIgnoreHardVmAffinity(true);
        }

        parameters.setReason(MessageBundler.getMessage(AuditLogType.MIGRATION_REASON_HOST_IN_MAINTENANCE));
        return runInternalAction(ActionType.MigrateMultipleVms,
                parameters,
                createMigrateVmsContext(parentContext))
                .getSucceeded();
    }

    protected CommandContext createMigrateVmsContext(ExecutionContext parentContext) {
        ExecutionContext ctx = new ExecutionContext();
        try {
            Step step = executionHandler.addSubStep(getExecutionContext(),
                    parentContext.getJob().getStep(StepEnum.EXECUTING),
                    StepEnum.MIGRATE_MULTIPLE_VMS,
                    null);
            ctx.setJob(parentContext.getJob());
            ctx.setStep(step);
            ctx.setMonitored(true);
        } catch (RuntimeException e) {
            log.error("Failed to create ExecutionContext for MigrateVmCommand", e);
        }
        return cloneContextAndDetachFromParent().withExecutionContext(ctx);
    }

    private boolean executeValidation() {
        Guid vdsId = getVdsId();
        VDS vds = vdsDao.get(vdsId);
        // we can get here when vds status was set already to Maintenance
        if (vds.getStatus() != VDSStatus.Maintenance && vds.getStatus() != VDSStatus.NonResponsive
                && vds.getStatus() != VDSStatus.Up && vds.getStatus() != VDSStatus.Error
                && vds.getStatus() != VDSStatus.PreparingForMaintenance && vds.getStatus() != VDSStatus.Down
                && vds.getStatus() != VDSStatus.InstallFailed) {
            return failValidation(EngineMessage.VDS_CANNOT_MAINTENANCE_VDS_IS_NOT_OPERATIONAL);
        }

        orderListOfRunningVmsOnVds(vdsId);

        for (VM vm : vms) {
            if (vm.isHostedEngine()) {
                // Check if there are available Hosted Engine hosts for that VM
                if (!HostedEngineHelper.haveHostsAvailableforHE(
                        vdsDao.getAllForClusterWithStatus(vds.getClusterId(), VDSStatus.Up),
                        Collections.singletonList(vdsId))) {
                    return failValidation(EngineMessage.VDS_CANNOT_MAINTENANCE_NO_ALTERNATE_HOST_FOR_HOSTED_ENGINE);
                }
                // The Hosted Engine vm is migrated by the HA agent
                continue;
            }
            if ((getParameters().isInternal() && vm.getMigrationSupport() == MigrationSupport.IMPLICITLY_NON_MIGRATABLE) ||
                    vm.getMigrationSupport() == MigrationSupport.PINNED_TO_HOST) {
                nonMigratableVms.add(vm.getName());
                return failValidation(EngineMessage.VDS_CANNOT_MAINTENANCE_IT_INCLUDES_NON_MIGRATABLE_VM);
            }
        }
        return true;
    }

    @Override
    protected boolean validate() {
        boolean result = executeValidation();
        if (!result) {
            executeValidationFailure();
        }
        return result;
    }

    private void executeValidationFailure() {
        addValidationMessageVariables();
        addMaintenanceFailedReason();
    }

    private void addValidationMessageVariables() {
        addValidationMessageVariable("HostsList", StringUtils.join(jobProperties.values(), ", "));
        addValidationMessageVariable("VmsList", StringUtils.join(nonMigratableVms, ", "));
    }

    private void addMaintenanceFailedReason() {
        String messageToDisplay = String.join(",",
                backend.getErrorsTranslator().translateErrorText(getReturnValue().getValidationMessages()));
        addCustomValue("Message", messageToDisplay);
        auditLogDirector.log(this, AuditLogType.GENERIC_ERROR_MESSAGE);
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        if (getParameters().isInternal()) {
            if (isSucceededWithHA()) {
                return AuditLogType.VDS_MAINTENANCE;
            } else if (getSucceeded()) {
                return AuditLogType.VDS_MAINTENANCE_MANUAL_HA;
            } else {
                return AuditLogType.VDS_MAINTENANCE_FAILED;
            }
        } else {
            if (isSucceededWithReasonGiven()){
                addCustomValue("Reason", getVds().getMaintenanceReason());
                return AuditLogType.USER_VDS_MAINTENANCE;
            } else if(isSucceededWithoutReasonGiven()) {
                return AuditLogType.USER_VDS_MAINTENANCE_WITHOUT_REASON;
            } else if (getSucceeded()) {
                return AuditLogType.USER_VDS_MAINTENANCE_MANUAL_HA;
            } else {
                return AuditLogType.USER_VDS_MAINTENANCE_MIGRATION_FAILED;
            }
        }
    }

    @Override
    public Map<String, String> getJobMessageProperties() {
        if (jobProperties == null) {
            jobProperties = Collections.singletonMap(VdcObjectType.VDS.name().toLowerCase(), getVdsName());
        }

        return jobProperties;
    }

    private boolean isHostedEngineOnVds() {
        return vms.stream().anyMatch(VM::isHostedEngine);
    }

    @Override
    public CommandCallback getCallback() {
        if (getVds().getClusterSupportsGlusterService() && getParameters().isStopGlusterService()) {
            return Injector.injectMembers(new HostMaintenanceCallback());
        } else {
            return super.getCallback();
        }
    }

    private boolean isSucceededWithHA() {
        return getSucceeded() && !haMaintenanceFailed;
    }

    private boolean isSucceededWithReasonGiven(){
        return isSucceededWithHA() && StringUtils.isNotEmpty(getVds().getMaintenanceReason());
    }

    private boolean isSucceededWithoutReasonGiven(){
        return isSucceededWithHA() && !haMaintenanceFailed && StringUtils.isEmpty(getVds().getMaintenanceReason());
    }
}
