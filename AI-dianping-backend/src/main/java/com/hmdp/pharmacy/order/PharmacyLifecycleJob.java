package com.hmdp.pharmacy.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyLifecycleJob {
    private final PharmacyLifecycleService lifecycle;

    public PharmacyLifecycleJob(PharmacyLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.seckill.lifecycle-delay-ms:30000}")
    public void releaseEndedActivityStock() {
        try {
            int count = lifecycle.releaseEndedActivities();
            if (count > 0) log.info("已结束 {} 个预约活动并释放剩余批次库存", count);
        } catch (RuntimeException ex) {
            log.error("释放到期活动库存失败", ex);
        }
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.seckill.lifecycle-delay-ms:30000}")
    public void expirePickupOrders() {
        try {
            int count = lifecycle.expirePickupOrders();
            if (count > 0) log.info("已释放 {} 个超时未取订单", count);
        } catch (RuntimeException ex) {
            log.error("释放超时订单库存失败", ex);
        }
    }
}
