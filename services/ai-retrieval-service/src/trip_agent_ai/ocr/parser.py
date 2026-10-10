import structlog

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.guardrails import require_safe_prompt_input
from trip_agent_ai.llm.document_vision import (
    DeepseekDocumentVisionExtractor,
    MockDocumentVisionExtractor,
)
from trip_agent_ai.ocr.document_validation import DocumentValidator
from trip_agent_ai.ocr.engine import MockOcrEngine, TesseractOcrEngine
from trip_agent_ai.schemas.booking import BookingExtractionResult

logger = structlog.get_logger(__name__)


class DocumentParser:
    def __init__(self, settings: Settings) -> None:
        self._validator = DocumentValidator(settings)
        self._mode = settings.document_parser_mode
        if self._mode == "mock":
            self._ocr = MockOcrEngine()
            self._vision = MockDocumentVisionExtractor(settings)
        else:
            self._ocr = TesseractOcrEngine(settings)
            self._vision = DeepseekDocumentVisionExtractor(settings)
        logger.warning("document_parser_mode_selected", parserMode=self._mode.upper())

    async def parse(
        self,
        filename: str,
        claimed_media_type: str,
        content: bytes,
        locale: str,
    ) -> BookingExtractionResult:
        inspection = self._validator.inspect(
            filename,
            claimed_media_type,
            content,
        )
        ocr = await self._ocr.extract(
            filename,
            content,
            inspection.media_type,
            locale,
        )
        require_safe_prompt_input(ocr.text)
        return await self._vision.extract(filename, ocr)
