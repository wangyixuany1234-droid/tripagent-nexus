import re
import unicodedata
from urllib.parse import urlparse

import structlog

from trip_agent_ai.core.errors import AppError

logger = structlog.get_logger(__name__)
UNTRUSTED_RETRIEVAL_PREFIX = "[UNTRUSTED_RETRIEVAL_DATA] "
MAX_ISOLATED_TEXT_LENGTH = 1000
_INJECTION_RULES = (
    (
        "INSTRUCTION_OVERRIDE",
        r"(ignore|forget|disregard).{0,40}(previous|prior|system).{0,20}"
        r"(instruction|prompt)",
    ),
    (
        "INSTRUCTION_OVERRIDE",
        r"(忽略|无视).{0,20}(系统|之前|原有).{0,20}(规则|指令|提示词|政策)",
    ),
    (
        "PROMPT_EXFILTRATION",
        r"(reveal|show|print|repeat).{0,30}(system|developer).{0,20}"
        r"(prompt|message|instruction)",
    ),
    (
        "SENSITIVE_DATA_EXFILTRATION",
        r"(reveal|show|print|dump|expose).{0,40}"
        r"(api\s*key|secret|credential|environment variable|internal config)",
    ),
    (
        "SENSITIVE_DATA_EXFILTRATION",
        r"(输出|显示|打印|泄露).{0,30}"
        r"(系统提示词|内部配置|api\s*key|密钥|内部上下文|系统上下文)",
    ),
    ("ROLE_SPOOFING", r"(^|\n)\s*(system|developer|assistant)\s*[:：]"),
    ("JAILBREAK", r"\b(jailbreak|developer mode|do anything now|dan mode)\b"),
    (
        "TOOL_OVERRIDE",
        r"(call|invoke|execute).{0,30}(tool|function).{0,30}"
        r"(without|bypass).{0,20}(approval|confirm|policy)",
    ),
    (
        "TOOL_OVERRIDE",
        r"(调用|执行).{0,20}(未授权|未经授权).{0,20}(工具|函数)",
    ),
    (
        "POLICY_BYPASS",
        r"(bypass|skip|disable|override).{0,40}"
        r"(budget|time|rule|policy|constraint)",
    ),
    ("POLICY_BYPASS", r"(跳过|绕过|无视).{0,30}(预算|时间|规则|政策|限制)"),
    (
        "COST_ACTION_BYPASS",
        r"(execute|make|process|cancel).{0,30}"
        r"(payment|booking|reservation).{0,30}"
        r"(without|bypass|skip).{0,20}(confirmation|approval)",
    ),
    (
        "COST_ACTION_BYPASS",
        r"直接.{0,20}(支付|付款|退订|取消预订).{0,30}"
        r"(不需要|无需|不要等待|绕过).{0,20}(确认|审批)",
    ),
    (
        "USER_DATA_EXFILTRATION",
        r"(读取|访问|获取).{0,20}(其他|别的).{0,10}用户.{0,20}(数据|记忆|信息)",
    ),
    (
        "EXTERNAL_CONTEXT_EXFILTRATION",
        r"(call|send|post|request).{0,100}https?://.{0,100}"
        r"(internal context|system context|secret|credential)",
    ),
    (
        "GOAL_OVERRIDE",
        r"ignore.{0,30}user.?s.{0,20}(goal|request|intent).{0,40}"
        r"(follow|obey).{0,30}(document|content)",
    ),
    (
        "EVIDENCE_FABRICATION",
        r"(fabricate|forge|invent).{0,40}(evidence|source|citation)",
    ),
)
_COMPILED_INJECTION_RULES = tuple(
    (code, re.compile(expression, re.IGNORECASE | re.DOTALL))
    for code, expression in _INJECTION_RULES
)


def _normalize_for_detection(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    return "".join(
        character for character in normalized if unicodedata.category(character) != "Cf"
    ).casefold()


def _prompt_injection_signals(value: str | None) -> tuple[str, ...]:
    if value is None or not value.strip():
        return ()
    normalized = _normalize_for_detection(value)
    return tuple(
        code
        for code, pattern in _COMPILED_INJECTION_RULES
        if pattern.search(normalized)
    )


def _record_prompt_rejection(signals: tuple[str, ...], source: str) -> None:
    logger.warning(
        "prompt_injection_rejected",
        code="PROMPT_INJECTION_DETECTED",
        source=source,
        signals=signals,
    )


def contains_prompt_injection(value: str | None) -> bool:
    return bool(_prompt_injection_signals(value))


def require_safe_prompt_input(value: str | None) -> None:
    signals = _prompt_injection_signals(value)
    if signals:
        _record_prompt_rejection(signals, "direct_input")
        raise AppError(
            code="PROMPT_INJECTION_DETECTED",
            title="Prompt injection detected",
            status=422,
            detail="The supplied text contains instruction-manipulation patterns.",
        )


def isolate_retrieval_text(value: str) -> str | None:
    signals = _prompt_injection_signals(value)
    if signals:
        _record_prompt_rejection(signals, "retrieval_content")
        return None
    normalized = " ".join(value.split())
    if not normalized:
        return None
    if normalized.startswith(UNTRUSTED_RETRIEVAL_PREFIX):
        normalized = normalized[len(UNTRUSTED_RETRIEVAL_PREFIX) :]
    content_limit = MAX_ISOLATED_TEXT_LENGTH - len(UNTRUSTED_RETRIEVAL_PREFIX)
    return UNTRUSTED_RETRIEVAL_PREFIX + normalized[:content_limit]


def require_allowed_https_url(url: str, allowed_hosts: tuple[str, ...]) -> None:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    allowed = {item.lower() for item in allowed_hosts}
    local = host in {"localhost", "127.0.0.1"}
    insecure = parsed.scheme != "https" and not (local and parsed.scheme == "http")
    if host not in allowed or insecure:
        raise ValueError("EXTERNAL_TOOL_DOMAIN_DENIED")
