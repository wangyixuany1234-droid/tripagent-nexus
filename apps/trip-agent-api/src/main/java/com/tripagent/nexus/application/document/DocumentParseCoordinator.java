package com.tripagent.nexus.application.document;

import com.tripagent.nexus.application.service.DocumentApplicationService;
import com.tripagent.nexus.application.service.DocumentApplicationService.Completion;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.config.DocumentProcessingProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.integration.document.AiDocumentParserClient;
import com.tripagent.nexus.integration.document.DocumentParseEvents.Requested;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentParseCoordinator {

  private static final Logger log = LoggerFactory.getLogger(DocumentParseCoordinator.class);

  private final DocumentRepository documentRepository;
  private final DocumentApplicationService documentService;
  private final TaskApplicationService taskService;
  private final AiDocumentParserClient parserClient;
  private final DocumentProcessingProperties properties;

  public DocumentParseCoordinator(
      DocumentRepository documentRepository,
      DocumentApplicationService documentService,
      TaskApplicationService taskService,
      AiDocumentParserClient parserClient,
      DocumentProcessingProperties properties) {
    this.documentRepository = documentRepository;
    this.documentService = documentService;
    this.taskService = taskService;
    this.parserClient = parserClient;
    this.properties = properties;
  }

  public void process(Requested request) {
    TaskRecord current = taskService.get(request.taskId());
    if (current.status().isTerminal() || current.status() == TaskStatus.WAITING_CONFIRMATION) {
      return;
    }
    try {
      if (current.status() == TaskStatus.QUEUED) {
        taskService.transition(
            request.taskId(), TaskStatus.RUNNING, "OCR_STARTED", 15, "OCR processing started");
        documentService.markParsing(request.payload().documentId());
      }
      DocumentEntity document =
          documentRepository.findById(request.payload().documentId()).orElseThrow();
      byte[] content = documentService.readContent(document.getDocumentId());
      BookingExtractionResult result =
          parserClient.parse(
              document, request.taskId(), content, properties.getLocale(), request.traceId());
      if ("MOCK".equals(result.parserMode())) {
        log.warn(
            "Document parsed with MockProvider documentId={} taskId={}",
            document.getDocumentId(),
            request.taskId());
      }
      taskService.transition(
          request.taskId(),
          TaskStatus.RUNNING,
          "RULE_VALIDATION",
          75,
          "Deterministic booking validation started");
      Completion completion = documentService.complete(request, result);
      if (completion.needsReview()) {
        taskService.transition(
            request.taskId(),
            TaskStatus.WAITING_CONFIRMATION,
            "NEEDS_REVIEW",
            90,
            "Human review is required");
      } else {
        taskService.transition(
            request.taskId(), TaskStatus.SUCCEEDED, "COMPLETED", 100, "Document parsing completed");
      }
    } catch (RuntimeException exception) {
      AsyncOperationException failure =
          exception instanceof AsyncOperationException asyncException
              ? asyncException
              : new AsyncOperationException(
                  "DOCUMENT_PROCESSING_FAILED",
                  false,
                  "Document processing could not be completed.",
                  exception);
      if (failure.retryable()) {
        TaskRecord task = taskService.get(request.taskId());
        if (!task.status().isTerminal()) {
          taskService.transition(
              request.taskId(),
              TaskStatus.RUNNING,
              "RETRY_SCHEDULED",
              task.progress(),
              "Document parser is temporarily unavailable; retry scheduled");
        }
      } else {
        fail(request, failure);
      }
      throw failure;
    }
  }

  private void fail(Requested request, RuntimeException exception) {
    log.error(
        "Document parsing failed documentId={} taskId={} errorType={}",
        request.payload().documentId(),
        request.taskId(),
        exception.getClass().getSimpleName());
    try {
      documentService.markFailed(request.payload().documentId());
      TaskRecord current = taskService.get(request.taskId());
      if (!current.status().isTerminal()) {
        taskService.fail(
            request.taskId(),
            exception instanceof AsyncOperationException asyncException
                ? asyncException.code()
                : "DOCUMENT_PROCESSING_FAILED",
            exception instanceof AsyncOperationException asyncException
                ? asyncException.userMessage()
                : "Document parsing failed",
            false);
      }
    } catch (RuntimeException stateException) {
      log.error(
          "Could not persist document parse failure documentId={} taskId={} errorType={}",
          request.payload().documentId(),
          request.taskId(),
          stateException.getClass().getSimpleName());
    }
  }
}
