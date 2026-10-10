import asyncio
import tempfile
from dataclasses import dataclass
from pathlib import Path

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError


@dataclass(frozen=True, slots=True)
class OcrPage:
    page: int
    text: str
    image: bytes
    media_type: str


@dataclass(frozen=True, slots=True)
class OcrResult:
    text: str
    pages: tuple[OcrPage, ...]
    engine: str


class TesseractOcrEngine:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def extract(
        self,
        filename: str,
        content: bytes,
        media_type: str,
        locale: str,
    ) -> OcrResult:
        del filename
        with tempfile.TemporaryDirectory(prefix="tripagent-ocr-") as directory:
            root = Path(directory)
            image_paths = (
                await self._render_pdf(root, content)
                if media_type == "application/pdf"
                else [self._write_image(root, content, media_type)]
            )
            pages = []
            for index, image_path in enumerate(image_paths, start=1):
                text = await self._run_tesseract(image_path, locale)
                pages.append(
                    OcrPage(
                        page=index,
                        text=text,
                        image=image_path.read_bytes(),
                        media_type="image/png"
                        if media_type == "application/pdf"
                        else media_type,
                    )
                )
            combined = "\n\n".join(f"[Page {page.page}]\n{page.text}" for page in pages)
            return OcrResult(
                text=combined[:100_000],
                pages=tuple(pages),
                engine="tesseract",
            )

    async def _render_pdf(self, root: Path, content: bytes) -> list[Path]:
        source = root / "source.pdf"
        source.write_bytes(content)
        info = await self._run(self._settings.pdfinfo_command, str(source))
        page_count = _parse_pdf_page_count(info)
        if page_count > self._settings.upload_max_pdf_pages:
            raise _ocr_error("UPLOAD_PAGE_LIMIT_EXCEEDED")
        output_prefix = root / "page"
        await self._run(
            self._settings.pdftoppm_command,
            "-f",
            "1",
            "-l",
            str(page_count),
            "-r",
            "150",
            "-png",
            str(source),
            str(output_prefix),
        )
        pages = await asyncio.to_thread(lambda: sorted(root.glob("page-*.png")))
        if len(pages) != page_count:
            raise _ocr_error("OCR_RENDER_FAILED")
        return pages

    def _write_image(self, root: Path, content: bytes, media_type: str) -> Path:
        extension = {
            "image/jpeg": ".jpg",
            "image/png": ".png",
            "image/webp": ".webp",
        }[media_type]
        target = root / f"source{extension}"
        target.write_bytes(content)
        return target

    async def _run_tesseract(self, image_path: Path, locale: str) -> str:
        language = "chi_sim+eng" if locale.lower().startswith("zh") else "eng"
        return await self._run(
            self._settings.tesseract_command,
            str(image_path),
            "stdout",
            "-l",
            language,
            "--psm",
            "6",
        )

    async def _run(self, *command: str) -> str:
        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except OSError as exception:
            raise _ocr_error("OCR_ENGINE_UNAVAILABLE") from exception
        try:
            stdout, _stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=self._settings.document_parser_timeout_seconds,
            )
        except TimeoutError as exception:
            process.kill()
            await process.wait()
            raise _ocr_error("OCR_TIMEOUT") from exception
        if process.returncode != 0:
            raise _ocr_error("OCR_PROCESSING_FAILED")
        return stdout.decode("utf-8", errors="replace").strip()


class MockOcrEngine:
    async def extract(
        self,
        filename: str,
        content: bytes,
        media_type: str,
        locale: str,
    ) -> OcrResult:
        del content, locale
        return OcrResult(
            text=f"[MOCK OCR fixture={Path(filename).stem}]",
            pages=(OcrPage(page=1, text="", image=b"", media_type=media_type),),
            engine="mock-ocr",
        )


def _parse_pdf_page_count(output: str) -> int:
    for line in output.splitlines():
        if line.lower().startswith("pages:"):
            try:
                pages = int(line.split(":", maxsplit=1)[1].strip())
            except ValueError as exception:
                raise _ocr_error("UPLOAD_FILE_MALFORMED") from exception
            if pages > 0:
                return pages
    raise _ocr_error("UPLOAD_FILE_MALFORMED")


def _ocr_error(code: str) -> AppError:
    return AppError(
        code=code,
        title="Document OCR failed",
        status=422,
        detail="OCR could not safely process the document.",
    )
