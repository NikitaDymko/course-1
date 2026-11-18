package io.github.mitohondriyaa.notification.controller;

import io.github.mitohondriyaa.notification.model.CallTask;
import io.github.mitohondriyaa.notification.model.NotificationRecord;
import io.github.mitohondriyaa.notification.service.NotificationJournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/notification")
public class NotificationController {
    private final NotificationJournalService journalService;

    @GetMapping("/journal")
    @ResponseStatus(HttpStatus.OK)
    public List<NotificationRecord> getJournal() {
        return journalService.getJournal();
    }

    @GetMapping("/journal/{orderNumber}")
    @ResponseStatus(HttpStatus.OK)
    public List<NotificationRecord> getJournalByOrder(@PathVariable String orderNumber) {
        return journalService.getJournalByOrder(orderNumber);
    }

    @GetMapping("/call-tasks")
    @ResponseStatus(HttpStatus.OK)
    public List<CallTask> getCallTasks() {
        return journalService.getCallTasks();
    }
}
