package ruc.db;

import java.util.Locale;
import java.util.ResourceBundle;

import org.slf4j.helpers.MessageFormatter;

/**
 * 消息资源：{@link #getRb()} 为英文（与历史行为一致）；{@link #getRbZh()} 为简体中文。
 * {@link #formatBilingual(String, Object...)} 用于日志等场景的中英并列输出。
 */
public class LanguageManager {
    private final Locale lc = Locale.of("en", "US");
    private final ResourceBundle rb = ResourceBundle.getBundle("messageResource", lc);
    private final ResourceBundle rbZh = ResourceBundle.getBundle("messageResource", Locale.of("zh", "CN"));
    private static final LanguageManager INSTANCE = new LanguageManager();

    public static LanguageManager getInstance() {
        return INSTANCE;
    }

    /** 英文资源（默认），与既有 {@code rb.getString(...)} 用法兼容。 */
    public ResourceBundle getRb() {
        return rb;
    }

    /** 简体中文资源。 */
    public ResourceBundle getRbZh() {
        return rbZh;
    }

    /**
     * 生成「中文 | English」单行文案，占位符与 SLF4J 一致（{@code {}}）。
     */
    public String formatBilingual(String key, Object... args) {
        String zh = MessageFormatter.arrayFormat(rbZh.getString(key), args).getMessage();
        String en = MessageFormatter.arrayFormat(rb.getString(key), args).getMessage();
        return zh + " | " + en;
    }
}
