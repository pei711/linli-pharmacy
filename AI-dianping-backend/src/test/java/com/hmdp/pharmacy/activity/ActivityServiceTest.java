package com.hmdp.pharmacy.activity;

import com.hmdp.pharmacy.medicine.MedicineService;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityServiceTest {
    private JdbcTemplate jdbc;
    private MedicineService medicines;
    private ActivityInventoryService inventory;
    private ActivityRedisService redis;
    private ActivityService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        medicines = mock(MedicineService.class);
        inventory = mock(ActivityInventoryService.class);
        redis = mock(ActivityRedisService.class);
        service = new ActivityService(jdbc, medicines, inventory, redis, mock(CacheClient.class));
    }

    @Test
    void onlyEnabledOverTheCounterProductsCanCreateActivities() {
        doReturn(List.of()).when(jdbc).queryForList(anyString(), eq(11L), eq(22L));

        assertThrows(IllegalArgumentException.class, () -> service.create(11, 22, "winter supply", 10, 1,
                LocalDateTime.of(2099, 1, 1, 10, 0), LocalDateTime.of(2099, 1, 2, 10, 0)));

        verify(inventory, never()).create(anyLong(), anyLong(), anyString(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void publicationReservesDatabaseBatchesThenInitializesRedisAndMarksPublished() {
        ActivityInventoryService.Publication publication = new ActivityInventoryService.Publication(
                7, 11, 22, 15, 1, LocalDateTime.of(2099, 1, 1, 10, 0),
                LocalDateTime.of(2099, 1, 2, 10, 0), true);
        when(inventory.preparePublication(7)).thenReturn(publication);
        when(redis.initialize(eq(publication), anyLong())).thenReturn(1L);

        service.publish(7);

        verify(inventory).markPublished(7);
    }

    @Test
    void redisMismatchKeepsActivityInPublishingForSafeRecovery() {
        ActivityInventoryService.Publication publication = new ActivityInventoryService.Publication(
                7, 11, 22, 15, 1, LocalDateTime.of(2099, 1, 1, 10, 0),
                LocalDateTime.of(2099, 1, 2, 10, 0), true);
        when(inventory.preparePublication(7)).thenReturn(publication);
        when(redis.initialize(eq(publication), anyLong())).thenReturn(-1L);

        assertThrows(IllegalStateException.class, () -> service.publish(7));

        verify(inventory, never()).markPublished(7);
    }
}
