package com.compact.crm.service;

import com.compact.crm.entity.ActivityType;
import com.compact.crm.repository.ActivityTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The default activity types are only ever added to a database that has
// none at all - an existing list (e.g. production's) is never changed.
@ExtendWith(MockitoExtension.class)
class ActivityTypeSeedTest {

    @Mock private ActivityTypeRepository repository;

    @InjectMocks private ActivityTypeService service;

    @Test
    void anEmptyDatabase_getsTheDefaultActivityTypes() {

        when(repository.count()).thenReturn(0L);

        assertThat(service.seedDefaultsIfEmpty()).isEqualTo(8);

        ArgumentCaptor<ActivityType> saved = ArgumentCaptor.forClass(ActivityType.class);
        verify(repository, times(8)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ActivityType::getName)
                .containsExactly("Call", "Email", "Meeting", "Visit", "Demo", "Quotation", "WhatsApp", "Other");
    }

    @Test
    void aDatabaseWithAnyActivityType_isLeftUntouched() {

        when(repository.count()).thenReturn(1L);

        assertThat(service.seedDefaultsIfEmpty()).isZero();

        verify(repository, never()).save(any());
    }
}
