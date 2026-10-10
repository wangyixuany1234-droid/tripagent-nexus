package com.tripagent.nexus.guardrail;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PromptInjectionGuard {

  private static final Logger LOGGER = LoggerFactory.getLogger(PromptInjectionGuard.class);
  private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}");
  private static final List<Rule> RULES =
      List.of(
          rule(
              "INSTRUCTION_OVERRIDE",
              "(ignore|forget|disregard).{0,40}(previous|prior|system).{0,20}(instruction|prompt)"),
          rule("INSTRUCTION_OVERRIDE", "(忽略|无视).{0,20}(系统|之前|原有).{0,20}(规则|指令|提示词|政策)"),
          rule(
              "PROMPT_EXFILTRATION",
              "(reveal|show|print|repeat).{0,30}(system|developer).{0,20}(prompt|message|instruction)"),
          rule(
              "SENSITIVE_DATA_EXFILTRATION",
              "(reveal|show|print|dump|expose).{0,40}"
                  + "(api\\s*key|secret|credential|environment variable|internal config)"),
          rule(
              "SENSITIVE_DATA_EXFILTRATION",
              "(输出|显示|打印|泄露).{0,30}" + "(系统提示词|内部配置|api\\s*key|密钥|内部上下文|系统上下文)"),
          rule("ROLE_SPOOFING", "(^|\\n)\\s*(system|developer|assistant)\\s*[:：]"),
          rule("JAILBREAK", "\\b(jailbreak|developer mode|do anything now|dan mode)\\b"),
          rule(
              "TOOL_OVERRIDE",
              "(call|invoke|execute).{0,30}(tool|function).{0,30}"
                  + "(without|bypass).{0,20}(approval|confirm|policy)"),
          rule("TOOL_OVERRIDE", "(调用|执行).{0,20}(未授权|未经授权).{0,20}(工具|函数)"),
          rule(
              "POLICY_BYPASS",
              "(bypass|skip|disable|override).{0,40}(budget|time|rule|policy|constraint)"),
          rule("POLICY_BYPASS", "(跳过|绕过|无视).{0,30}(预算|时间|规则|政策|限制)"),
          rule(
              "COST_ACTION_BYPASS",
              "(execute|make|process|cancel).{0,30}(payment|booking|reservation).{0,30}"
                  + "(without|bypass|skip).{0,20}(confirmation|approval)"),
          rule(
              "COST_ACTION_BYPASS",
              "直接.{0,20}(支付|付款|退订|取消预订).{0,30}" + "(不需要|无需|不要等待|绕过).{0,20}(确认|审批)"),
          rule("USER_DATA_EXFILTRATION", "(读取|访问|获取).{0,20}(其他|别的).{0,10}用户.{0,20}(数据|记忆|信息)"),
          rule(
              "EXTERNAL_CONTEXT_EXFILTRATION",
              "(call|send|post|request).{0,100}https?://.{0,100}"
                  + "(internal context|system context|secret|credential)"),
          rule(
              "GOAL_OVERRIDE",
              "ignore.{0,30}user.?s.{0,20}(goal|request|intent).{0,40}"
                  + "(follow|obey).{0,30}(document|content)"),
          rule(
              "EVIDENCE_FABRICATION", "(fabricate|forge|invent).{0,40}(evidence|source|citation)"));

  private final GuardrailProperties properties;

  public PromptInjectionGuard(GuardrailProperties properties) {
    this.properties = properties;
  }

  public Decision inspect(String text) {
    if (!properties.isPromptInjectionEnabled() || text == null || text.isBlank()) {
      return new Decision(true, List.of());
    }
    return detect(text);
  }

  public static Decision detect(String text) {
    if (text == null || text.isBlank()) {
      return new Decision(true, List.of());
    }
    String normalized = normalize(text);
    List<String> signals = new ArrayList<>();
    for (Rule rule : RULES) {
      if (rule.pattern().matcher(normalized).find()) {
        signals.add(rule.code());
      }
    }
    return new Decision(signals.isEmpty(), List.copyOf(signals));
  }

  public void requireSafe(String text) {
    Decision decision = inspect(text);
    if (!decision.allowed()) {
      recordRejection(decision, "direct_input");
      throw new ApiException(
          "PROMPT_INJECTION_DETECTED",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "The supplied text contains instruction-manipulation patterns.");
    }
  }

  void recordRejection(Decision decision, String source) {
    LOGGER.warn("PROMPT_INJECTION_DETECTED source={} signals={}", source, decision.signals());
  }

  private static String normalize(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    return FORMAT_CHARACTERS.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
  }

  private static Rule rule(String code, String expression) {
    return new Rule(code, Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
  }

  public record Decision(boolean allowed, List<String> signals) {}

  private record Rule(String code, Pattern pattern) {}
}
