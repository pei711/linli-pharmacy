package com.hmdp.pharmacy.order;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
@RequestMapping("/api/v1")
public class PharmacyOrderController {
    private final PharmacyOrderService orders;

    public PharmacyOrderController(PharmacyOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/orders/{orderNo}")
    public Result getOrder(@PathVariable String orderNo) {
        return Result.ok(orders.findOrder(orderNo, currentUserId()));
    }

    @GetMapping("/orders/mine")
    public Result myOrders() {
        return Result.ok(orders.findOrdersByUser(currentUserId()));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public Result cancel(@PathVariable String orderNo) {
        return Result.ok(orders.cancel(orderNo, currentUserId()));
    }

    @PostMapping("/admin/orders/{orderNo}/pickup")
    public Result pickUp(@PathVariable String orderNo) {
        return Result.ok(orders.pickUp(orderNo));
    }

    private long currentUserId() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new SecurityException("请先登录");
        }
        return user.getId();
    }
}
