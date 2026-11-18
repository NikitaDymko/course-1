package io.github.mitohondriyaa.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mitohondriyaa.notification.model.CallTask;
import io.github.mitohondriyaa.notification.model.NotificationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Журнал уведомлений и задания на звонок. Простое хранение в Redis-списках.
 */
@Service
@RequiredArgsConstructor
public class NotificationJournalService {
    private static final String JOURNAL_KEY = "notification:journal";
    private static final String CALL_TASKS_KEY = "notification:call-tasks";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public void addRecord(NotificationRecord record) {
        try {
            stringRedisTemplate.opsForList()
                .rightPush(JOURNAL_KEY, objectMapper.writeValueAsString(record));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store notification record", e);
        }
    }

    public List<NotificationRecord> getJournal() {
        List<String> raw = stringRedisTemplate.opsForList().range(JOURNAL_KEY, 0, -1);
        List<NotificationRecord> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String json : raw) {
            try {
                result.add(objectMapper.readValue(json, NotificationRecord.class));
            } catch (Exception ignored) {
                // пропускаем повреждённые записи
            }
        }
        return result;
    }

    public void addCallTask(CallTask task) {
        try {
            stringRedisTemplate.opsForList()
                .rightPush(CALL_TASKS_KEY, objectMapper.writeValueAsString(task));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store call task", e);
        }
    }

    public List<CallTask> getCallTasks() {
        List<String> raw = stringRedisTemplate.opsForList().range(CALL_TASKS_KEY, 0, -1);
        List<CallTask> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String json : raw) {
            try {
                result.add(objectMapper.readValue(json, CallTask.class));
            } catch (Exception ignored) {
                // пропускаем повреждённые записи
            }
        }
        return result;
    }

    public List<NotificationRecord> getJournalByOrder(String orderNumber) {
        return getJournal().stream()
            .filter(record -> orderNumber.equals(record.getOrderNumber()))
            .toList();
    }
}
