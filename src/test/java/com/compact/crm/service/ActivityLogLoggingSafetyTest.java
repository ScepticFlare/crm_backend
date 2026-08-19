package com.compact.crm.service;

import com.compact.crm.entity.Employee;
import com.compact.crm.enums.ActivityAction;
import com.compact.crm.enums.ActivityModule;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;

/**
 * Pins the one property that matters most for a cross-cutting concern like
 * this: a failure while writing an activity log entry must never break the
 * CRM operation it's attached to. No Spring context needed - purely
 * verifying ActivityLogService.log()'s own try/catch.
 */
@ExtendWith(MockitoExtension.class)
class ActivityLogLoggingSafetyTest {

    @Mock private ActivityLogRepository activityLogRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private AccessControlService accessControlService;

    @Test
    void log_whenRepositorySaveThrows_doesNotPropagate() {

        ActivityLogService activityLogService =
                new ActivityLogService(activityLogRepository, currentUserService, accessControlService);

        doThrow(new RuntimeException("DB unavailable"))
                .when(activityLogRepository).save(any());

        Employee actor = Employee.builder().id(1L).name("Rahul").build();

        assertThatCode(() -> activityLogService.log(
                actor, ActivityModule.LEAD, ActivityAction.CREATE, 1L, "ABC Industries", "Created lead"
        )).doesNotThrowAnyException();
    }
}
