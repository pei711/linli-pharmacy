package com.hmdp.pharmacy.medicine;

import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.ShopOwnerMapper;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MedicineServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShopOwnerMapper owners = mock(ShopOwnerMapper.class);
    private final MedicineService service = new MedicineService(jdbc, owners, mock(CacheClient.class));

    @AfterEach
    void clearIdentity() { UserHolder.removeUser(); }

    @Test
    void rejectsAnotherStoresInventoryBeforeDatabaseRead() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(owners.findOwnerId(42L)).thenReturn(8L);
        assertThrows(SecurityException.class, () -> service.inventory(42));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMissingIdentityBeforeStoreLookup() {
        assertThrows(SecurityException.class, () -> service.create(42, "name", "spec", "OTC"));
        verifyNoInteractions(owners, jdbc);
    }

    @Test
    void rejectsInvalidInboundQuantityBeforeDatabaseMutation() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(owners.findOwnerId(42L)).thenReturn(7L);
        var error = assertThrows(IllegalArgumentException.class, () -> service.receiveBatch(
                42, 5, "B-1", java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(10), 0));
        assertEquals("批次日期或入库数量无效", error.getMessage());
        verifyNoInteractions(jdbc);
    }
}
