package com.hmdp.pharmacy.ai;

import com.hmdp.dto.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/ai/assistant")
public class PharmacyOperationsController {
    private final PharmacyOperationsChatService chat;

    public PharmacyOperationsController(PharmacyOperationsChatService chat) {
        this.chat = chat;
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message,
                             @Size(max = 64) String conversationId) { }

    @PostMapping("/chat")
    public Result chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(chat.chat(request.message(), request.conversationId()));
    }
}
