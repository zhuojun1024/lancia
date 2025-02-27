/*********************************************************************************
 *                                                                               *
 * The MIT License (MIT)                                                         *
 *                                                                               *
 * Copyright (c) 2015-2021 aoju.org and other contributors.                      *
 *                                                                               *
 * Permission is hereby granted, free of charge, to any person obtaining a copy  *
 * of this software and associated documentation files (the "Software"), to deal *
 * in the Software without restriction, including without limitation the rights  *
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell     *
 * copies of the Software, and to permit persons to whom the Software is         *
 * furnished to do so, subject to the following conditions:                      *
 *                                                                               *
 * The above copyright notice and this permission notice shall be included in    *
 * all copies or substantial portions of the Software.                           *
 *                                                                               *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR    *
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,      *
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE   *
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER        *
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, *
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN     *
 * THE SOFTWARE.                                                                 *
 *                                                                               *
 ********************************************************************************/
package org.aoju.lancia;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import org.aoju.bus.core.lang.Assert;
import org.aoju.bus.core.lang.Normal;
import org.aoju.bus.core.lang.exception.InstrumentException;
import org.aoju.bus.core.toolkit.CollKit;
import org.aoju.bus.core.toolkit.StringKit;
import org.aoju.lancia.kernel.browser.Context;
import org.aoju.lancia.kernel.page.TaskQueue;
import org.aoju.lancia.kernel.page.*;
import org.aoju.lancia.nimble.*;
import org.aoju.lancia.nimble.network.Cookie;
import org.aoju.lancia.nimble.network.CookieParam;
import org.aoju.lancia.nimble.network.DeleteCookie;
import org.aoju.lancia.nimble.page.FileChooserPayload;
import org.aoju.lancia.nimble.page.GetNavigationHistory;
import org.aoju.lancia.nimble.page.JavascriptDialogPayload;
import org.aoju.lancia.nimble.page.NavigationEntry;
import org.aoju.lancia.nimble.runtime.*;
import org.aoju.lancia.option.*;
import org.aoju.lancia.worker.*;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 页面信息
 *
 * @author Kimi Liu
 * @version 1.2.8
 * @since JDK 1.8+
 */
public class Page extends EventEmitter {

    private static final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor();
    private static final String ABOUT_BLANK = "about:blank";
    private static final Map<String, Double> unitToPixels = new HashMap<String, Double>() {
        {
            put("px", 1.00);
            put("in", 96.00);
            put("cm", 37.8);
            put("mm", 3.78);
        }
    };
    private final Set<FileChooserCallBack> fileChooserInterceptors;
    private final CDPSession client;
    private final Target target;
    private final Keyboard keyboard;
    private final Mouse mouse;
    private final Timeout timeout;
    private final Touchscreen touchscreen;
    private final Accessibility accessibility;
    private final FrameManager frameManager;
    private final EmulationManager emulationManager;
    private final Tracing tracing;
    private final Map<String, Function<List<?>, Object>> pageBindings;
    private final Coverage coverage;
    private final TaskQueue<String> screenshotTaskQueue;
    private final Map<String, Worker> workers;
    private boolean closed;
    private boolean javascriptEnabled;
    private Viewport viewport;

    public Page(CDPSession client, Target target, boolean ignoreHTTPSErrors, TaskQueue<String> screenshotTaskQueue) {
        super();
        this.closed = false;
        this.client = client;
        this.target = target;
        this.keyboard = new Keyboard(client);
        this.mouse = new Mouse(client, keyboard);
        this.timeout = new Timeout();
        this.touchscreen = new Touchscreen(client, keyboard);
        this.accessibility = new Accessibility(client);
        this.frameManager = new FrameManager(client, this, ignoreHTTPSErrors, timeout);
        this.emulationManager = new EmulationManager(client);
        this.tracing = new Tracing(client);
        this.pageBindings = new HashMap<>();
        this.coverage = new Coverage(client);
        this.javascriptEnabled = true;
        this.viewport = null;
        this.screenshotTaskQueue = screenshotTaskQueue;
        this.workers = new HashMap<>();
        BrowserListener<Target> attachedListener = new BrowserListener<Target>() {
            @Override
            public void onBrowserEvent(Target event) {
                Page page = (Page) this.getTarget();
                if (!"worker".equals(event.getTargetInfo().getType())) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("sessionId", event.getSessionId());
                    client.send("Target.detachFromTarget", params, false);
                    return;
                }
                CDPSession session = Connection.fromSession(page.client()).session(event.getSessionId());
                Worker worker = new Worker(session, event.getTargetInfo().getUrl(), page::addConsoleMessage, page::handleException);
                page.workers().putIfAbsent(event.getSessionId(), worker);
                page.emit(Variables.Event.PAGE_WORKERCREATED.getName(), worker);
            }
        };
        attachedListener.setMethod("Target.attachedToTarget");
        attachedListener.setTarget(this);
        attachedListener.setResolveType(Target.class);
        this.client.addListener(attachedListener.getMethod(), attachedListener);

        BrowserListener<Target> detachedListener = new BrowserListener<Target>() {
            @Override
            public void onBrowserEvent(Target event) {
                Page page = (Page) this.getTarget();
                Worker worker = page.workers().get(event.getSessionId());
                if (worker == null) {
                    return;
                }
                page.emit(Variables.Event.PAGE_WORKERDESTROYED.getName(), worker);
                page.workers().remove(event.getSessionId());
            }
        };
        detachedListener.setMethod("Target.detachedFromTarget");
        detachedListener.setTarget(this);
        detachedListener.setResolveType(Target.class);
        this.client.addListener(detachedListener.getMethod(), detachedListener);

        BrowserListener<Object> frameAttachedListener = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_FRAMEATTACHED.getName(), event);
            }
        };
        frameAttachedListener.setMethod(Variables.Event.FRAME_MANAGER_FRAME_ATTACHED.getName());
        frameAttachedListener.setTarget(this);
        this.frameManager.addListener(frameAttachedListener.getMethod(), frameAttachedListener);

        BrowserListener<Object> frameDetachedListener = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_FRAMEDETACHED.getName(), event);
            }
        };
        frameDetachedListener.setMethod(Variables.Event.FRAME_MANAGER_FRAME_DETACHED.getName());
        frameDetachedListener.setTarget(this);
        this.frameManager.addListener(frameDetachedListener.getMethod(), frameDetachedListener);

        BrowserListener<Object> frameNavigatedListener = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_FRAMENAVIGATED.getName(), event);
            }
        };
        frameNavigatedListener.setMethod(Variables.Event.FRAME_MANAGER_FRAME_NAVIGATED.getName());
        frameNavigatedListener.setTarget(this);
        this.frameManager.addListener(frameNavigatedListener.getMethod(), frameNavigatedListener);

        NetworkManager networkManager = this.frameManager.getNetworkManager();

        BrowserListener<Request> requestLis = new BrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_REQUEST.getName(), event);
            }
        };
        requestLis.setMethod(Variables.Event.NETWORK_MANAGER_REQUEST.getName());
        requestLis.setTarget(this);
        networkManager.addListener(requestLis.getMethod(), requestLis);

        BrowserListener<Response> responseLis = new BrowserListener<Response>() {
            @Override
            public void onBrowserEvent(Response event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_RESPONSE.getName(), event);
            }
        };
        responseLis.setMethod(Variables.Event.NETWORK_MANAGER_RESPONSE.getName());
        responseLis.setTarget(this);
        networkManager.addListener(responseLis.getMethod(), responseLis);

        BrowserListener<Request> requestFailedLis = new BrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_REQUESTFAILED.getName(), event);
            }
        };
        requestFailedLis.setMethod(Variables.Event.NETWORK_MANAGER_REQUEST_FAILED.getName());
        requestFailedLis.setTarget(this);
        networkManager.addListener(requestFailedLis.getMethod(), requestFailedLis);

        BrowserListener<Request> requestFinishedLis = new BrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_REQUESTFINISHED.getName(), event);
            }
        };
        requestFinishedLis.setMethod(Variables.Event.NETWORK_MANAGER_REQUEST_FINISHED.getName());
        requestFinishedLis.setTarget(this);
        networkManager.addListener(requestFinishedLis.getMethod(), requestFinishedLis);

        this.fileChooserInterceptors = new CopyOnWriteArraySet<>();

        BrowserListener<Object> domContentEventFiredLis = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_DOMContentLoaded.getName(), event);
            }
        };
        domContentEventFiredLis.setMethod("Page.domContentEventFired");
        domContentEventFiredLis.setTarget(this);
        this.client.addListener(domContentEventFiredLis.getMethod(), domContentEventFiredLis);

        BrowserListener<Object> loadEventFiredLis = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Variables.Event.PAGE_LOAD.getName(), event);
            }
        };
        loadEventFiredLis.setMethod("Page.loadEventFired");
        loadEventFiredLis.setTarget(this);
        this.client.addListener(loadEventFiredLis.getMethod(), loadEventFiredLis);

        BrowserListener<ConsoleCalledPayload> consoleAPICalledLis = new BrowserListener<ConsoleCalledPayload>() {
            @Override
            public void onBrowserEvent(ConsoleCalledPayload event) {
                Page page = (Page) this.getTarget();
                page.onConsoleAPI(event);
            }
        };
        consoleAPICalledLis.setMethod("Runtime.consoleAPICalled");
        consoleAPICalledLis.setTarget(this);
        this.client.addListener(consoleAPICalledLis.getMethod(), consoleAPICalledLis);

        BrowserListener<BindingCalledPayload> bindingCalledLis = new BrowserListener<BindingCalledPayload>() {
            @Override
            public void onBrowserEvent(BindingCalledPayload event) {
                Page page = (Page) this.getTarget();
                page.onBindingCalled(event);
            }
        };
        bindingCalledLis.setMethod("Runtime.bindingCalled");
        bindingCalledLis.setTarget(this);
        this.client.addListener(bindingCalledLis.getMethod(), bindingCalledLis);

        BrowserListener<JavascriptDialogPayload> javascriptDialogOpeningLis = new BrowserListener<JavascriptDialogPayload>() {
            @Override
            public void onBrowserEvent(JavascriptDialogPayload event) {
                Page page = (Page) this.getTarget();
                page.onDialog(event);
            }
        };
        javascriptDialogOpeningLis.setMethod("Page.javascriptDialogOpening");
        javascriptDialogOpeningLis.setTarget(this);
        this.client.addListener(javascriptDialogOpeningLis.getMethod(), javascriptDialogOpeningLis);

        BrowserListener<JSONObject> exceptionThrownLis = new BrowserListener<JSONObject>() {
            @Override
            public void onBrowserEvent(JSONObject event) {
                Page page = (Page) this.getTarget();
                JSONObject exceptionDetails = event.getJSONObject("exceptionDetails");
                if (exceptionDetails == null) {
                    return;
                }
                ExceptionDetails value = JSON.toJavaObject(exceptionDetails, ExceptionDetails.class);
                page.handleException(value);

            }
        };
        exceptionThrownLis.setMethod("Runtime.exceptionThrown");
        exceptionThrownLis.setTarget(this);
        this.client.addListener(exceptionThrownLis.getMethod(), exceptionThrownLis);

        BrowserListener<Object> targetCrashedLis = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.onTargetCrashed();
            }
        };
        targetCrashedLis.setMethod("Inspector.targetCrashed");
        targetCrashedLis.setTarget(this);
        this.client.addListener(targetCrashedLis.getMethod(), targetCrashedLis);

        BrowserListener<MetricPayload> metricsLis = new BrowserListener<MetricPayload>() {
            @Override
            public void onBrowserEvent(MetricPayload event) {
                Page page = (Page) this.getTarget();
                try {
                    page.emitMetrics(event);
                } catch (IllegalAccessException | IntrospectionException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        metricsLis.setMethod("Inspector.targetCrashed");
        metricsLis.setTarget(this);
        this.client.addListener(metricsLis.getMethod(), metricsLis);

        BrowserListener<EntryAddedPayload> entryAddedLis = new BrowserListener<EntryAddedPayload>() {
            @Override
            public void onBrowserEvent(EntryAddedPayload event) {
                Page page = (Page) this.getTarget();
                page.onLogEntryAdded(event);
            }
        };
        entryAddedLis.setMethod("Log.entryAdded");
        entryAddedLis.setTarget(this);
        this.client.addListener(entryAddedLis.getMethod(), entryAddedLis);

        BrowserListener<FileChooserPayload> fileChooserOpenedLis = new BrowserListener<FileChooserPayload>() {
            @Override
            public void onBrowserEvent(FileChooserPayload event) {
                Page page = (Page) this.getTarget();
                page.onFileChooser(event);
            }
        };
        fileChooserOpenedLis.setMethod("Page.fileChooserOpened");
        fileChooserOpenedLis.setTarget(this);
        this.client.addListener(fileChooserOpenedLis.getMethod(), fileChooserOpenedLis);

    }

    /**
     * 创建一个page对象
     *
     * @param client              与页面通讯的客户端
     * @param target              目标
     * @param ignoreHTTPSErrors   是否忽略https错误
     * @param viewport            视图
     * @param screenshotTaskQueue 截图队列
     * @return 页面实例
     * @throws ExecutionException   并发异常
     * @throws InterruptedException 线程打断异常
     */
    public static Page create(CDPSession client, Target target, boolean ignoreHTTPSErrors, Viewport viewport, TaskQueue<String> screenshotTaskQueue) throws ExecutionException, InterruptedException {
        Page page = new Page(client, target, ignoreHTTPSErrors, screenshotTaskQueue);
        page.initialize();
        if (viewport != null) {
            page.setViewport(viewport);
        }
        return page;
    }

    /**
     * 监听页面的关闭事件
     *
     * @param handler 要提供的处理器
     */
    public void onClose(EventHandler<Object> handler) {
        this.on(Variables.Event.PAGE_CLOSE.getName(), handler);
    }

    public void onConsole(EventHandler<ConsoleMessage> handler) {
        this.on(Variables.Event.PAGE_CONSOLE.getName(), handler);
    }

    public void onDialog(EventHandler<Dialog> handler) {
        this.on(Variables.Event.PAGE_DIALOG.getName(), handler);
    }

    public void onError(EventHandler<Error> handler) {
        this.on(Variables.Event.PAGE_ERROR.getName(), handler);
    }

    /**
     * frame attach的时候触发
     * 注意不要在这个事件内直接调用Frame中会暂停线程的方法
     * 不然的话，websocket的read线程会被阻塞，程序无法正常运行
     * 可以在将这些方法的调用移动到另外一个线程中
     *
     * @param handler 事件处理器
     */
    public void onFrameattached(EventHandler<Frame> handler) {
        this.on(Variables.Event.PAGE_FRAMEATTACHED.getName(), handler);
    }

    /**
     * frame detached的时候触发
     * 注意不要在这个事件内直接调用Frame中会暂停线程的方法
     * 不然的话，websocket的read线程会被阻塞，程序无法正常运行
     * 可以在将这些方法的调用移动到另外一个线程中
     *
     * @param handler 事件处理器
     */
    public void onFramedetached(EventHandler<Frame> handler) {
        this.on(Variables.Event.PAGE_FRAMEDETACHED.getName(), handler);
    }

    /**
     * 注意不要在这个事件内直接调用Frame中会暂停线程的方法
     * 不然的话，websocket的read线程会被阻塞，程序无法正常运行
     * 可以在将这些方法的调用移动到另外一个线程中
     *
     * @param handler 事件处理器
     */
    public void onFramenavigated(EventHandler<Frame> handler) {
        this.on(Variables.Event.PAGE_FRAMENAVIGATED.getName(), handler);
    }

    public void onLoad(EventHandler<Object> handler) {
        this.on(Variables.Event.PAGE_LOAD.getName(), handler);
    }

    public void onMetrics(EventHandler<PageMetric> handler) {
        this.on(Variables.Event.PAGE_METRICS.getName(), handler);
    }

    public void onPageerror(EventHandler<RuntimeException> handler) {
        this.on(Variables.Event.PAGE_ERROR.getName(), handler);
    }

    public void onPopup(EventHandler<Error> handler) {
        this.on(Variables.Event.PAGE_POPUP.getName(), handler);
    }

    public void onRequest(EventHandler<Request> handler) {
        this.on(Variables.Event.PAGE_REQUEST.getName(), handler);
    }

    public void onRequestfailed(EventHandler<Request> handler) {
        this.on(Variables.Event.PAGE_REQUESTFAILED.getName(), handler);
    }

    public void onRequestfinished(EventHandler<Request> handler) {
        this.on(Variables.Event.PAGE_REQUESTFINISHED.getName(), handler);
    }

    public void onResponse(EventHandler<Response> handler) {
        this.on(Variables.Event.PAGE_RESPONSE.getName(), handler);
    }

    /**
     * 注意不要在这个事件内直接调用Worker中会暂停线程的方法
     * 不然的话，websocket的read线程会被阻塞，程序无法正常运行
     * 可以在将这些方法的调用移动到另外一个线程中
     *
     * @param handler 事件处理器
     */
    public void onWorkercreated(EventHandler<Worker> handler) {
        this.on(Variables.Event.PAGE_WORKERCREATED.getName(), handler);
    }

    /**
     * 注意不要在这个事件内直接调用Worker中会暂停线程的方法
     * 不然的话，websocket的read线程会被阻塞，程序无法正常运行
     * 可以在将这些方法的调用移动到另外一个线程中
     *
     * @param handler 事件处理器
     */
    public void onWorkerdestroyed(EventHandler<Worker> handler) {
        this.on(Variables.Event.PAGE_WORKERDESTROYED.getName(), handler);
    }

    /**
     * 此方法在页面内执行 document.querySelector。如果没有元素匹配指定选择器，返回值是 null。
     *
     * @param selector 选择器
     * @return ElementHandle
     */
    public ElementHandle $(String selector) {
        return this.getMainFrame().$(selector);
    }

    /**
     * 此方法在页面内执行 document.querySelectorAll。如果没有元素匹配指定选择器，返回值是 []。
     *
     * @param selector 选择器
     * @return ElementHandle集合
     */
    public List<ElementHandle> $$(String selector) {
        return this.getMainFrame().$$(selector);
    }

    /**
     * 此方法在页面内执行 Array.from(document.querySelectorAll(selector))，然后把匹配到的元素数组作为第一个参数传给 pageFunction。
     *
     * @param selector     一个框架选择器
     * @param pageFunction 在浏览器实例上下文中要执行的方法
     * @return pageFunction 的返回值
     */
    public Object $$eval(String selector, String pageFunction) {
        return this.$$eval(selector, pageFunction, new ArrayList<>());
    }

    /**
     * 此方法在页面内执行 Array.from(document.querySelectorAll(selector))，然后把匹配到的元素数组作为第一个参数传给 pageFunction。
     *
     * @param selector     一个框架选择器
     * @param pageFunction 在浏览器实例上下文中要执行的方法
     * @param args         要传给 pageFunction 的参数。（比如你的代码里生成了一个变量，在页面中执行方法时需要用到，可以通过这个 args 传进去）
     * @return pageFunction 的返回值
     */
    public Object $$eval(String selector, String pageFunction, List<Object> args) {
        return this.getMainFrame().$$eval(selector, pageFunction, args);
    }

    /**
     * 返回主 Frame
     * 保证页面一直有有一个主 frame
     *
     * @return {@link Frame}
     */
    public Frame getMainFrame() {
        return this.frameManager.getMainFrame();
    }

    /**
     * 此方法在页面内执行 document.querySelector，然后把匹配到的元素作为第一个参数传给 pageFunction。
     *
     * @param selector     选择器
     * @param pageFunction 在浏览器实例上下文中要执行的方法
     * @return pageFunction 的返回值
     */
    public Object $eval(String selector, String pageFunction) {
        return this.$eval(selector, pageFunction, new ArrayList<>());
    }

    /**
     * 此方法在页面内执行 document.querySelector，然后把匹配到的元素作为第一个参数传给 pageFunction。
     *
     * @param selector     选择器
     * @param pageFunction 在浏览器实例上下文中要执行的方法
     * @param args         要传给 pageFunction 的参数。（比如你的代码里生成了一个变量，在页面中执行方法时需要用到，可以通过这个 args 传进去）
     * @return pageFunction 的返回值
     */
    public Object $eval(String selector, String pageFunction, List<Object> args) {
        return this.getMainFrame().$eval(selector, pageFunction, args);
    }

    /**
     * 此方法解析指定的XPath表达式。
     *
     * @param expression XPath表达式。
     * @return ElementHandle
     */
    public List<ElementHandle> $x(String expression) {
        return this.getMainFrame().$x(expression);
    }

    /**
     * 注入一个指定src(url)或者代码(content)的 script 标签到当前页面。
     *
     * @param options 可选参数
     * @return 注入完成的tag标签
     * @throws IOException 异常
     */
    public ElementHandle addScriptTag(ScriptTagOption options) throws IOException {
        return this.getMainFrame().addScriptTag(options);
    }

    /**
     * 添加一个指定link的 link rel="stylesheet" 标签。 或者添加一个指定代码(content)的 style type="text/css" 标签。
     *
     * @param options link标签
     * @return 注入完成的tag标签。当style的onload触发或者代码被注入到frame。
     * @throws IOException 异常
     */
    public ElementHandle addStyleTag(StyleTagOption options) throws IOException {
        return this.getMainFrame().addStyleTag(options);
    }

    /**
     * 为HTTP authentication 提供认证凭据 。
     * <p>
     * 传 null 禁用认证。
     *
     * @param credentials 验证信息
     */
    public void authenticate(Credentials credentials) {
        this.frameManager.getNetworkManager().authenticate(credentials);
    }

    /**
     * 相当于多个tab时，切换到某个tab。
     */
    public void bringToFront() {
        this.client.send("Page.bringToFront", null, true);
    }

    /**
     * 返回页面隶属的浏览器
     *
     * @return 浏览器实例
     */
    public Browser browser() {
        return this.target.browser();
    }

    /**
     * 返回默认的浏览器上下文
     *
     * @return 浏览器上下文
     */
    public Context browserContext() {
        return this.target.browserContext();
    }

    /**
     * 此方法找到一个匹配 selector 选择器的元素，如果需要会把此元素滚动到可视，然后通过 page.mouse 点击它。 如果选择器没有匹配任何元素，此方法将会报错。
     * 默认是阻塞的，会等待点击完成指令返回
     *
     * @param selector 选择器
     * @param isBlock  是否是阻塞的，不阻塞的时候可以配合waitFor方法使用
     * @throws InterruptedException 异常
     * @throws ExecutionException   异常
     */
    public void click(String selector, boolean isBlock) throws InterruptedException, ExecutionException {
        this.click(selector, new ClickOption(), isBlock);
    }

    /**
     * 此方法找到一个匹配 selector 选择器的元素，如果需要会把此元素滚动到可视，然后通过 page.mouse 点击它。 如果选择器没有匹配任何元素，此方法将会报错。
     * 默认是阻塞的，会等待点击完成指令返回
     *
     * @param selector 选择器
     * @throws InterruptedException 异常
     * @throws ExecutionException   异常
     */
    public void click(String selector) throws InterruptedException, ExecutionException {
        this.click(selector, new ClickOption(), true);
    }

    /**
     * 此方法找到一个匹配 selector 选择器的元素，如果需要会把此元素滚动到可视，然后通过 page.mouse 点击它。 如果选择器没有匹配任何元素，此方法将会报错。
     *
     * @param selector 选择器
     * @param options  参数
     * @param isBlock  是否是阻塞的，为true代表阻塞，为false代表不阻塞，不阻塞可以配合waitForNavigate等方法使用
     * @throws InterruptedException 异常
     * @throws ExecutionException   异常
     */
    public void click(String selector, ClickOption options, boolean isBlock) throws InterruptedException, ExecutionException {
        this.getMainFrame().click(selector, options, isBlock);
    }

    /**
     * 关闭页面
     *
     * @throws InterruptedException 异常
     */
    public void close() throws InterruptedException {
        this.close(false);
    }

    /**
     * page.close() 在 beforeunload 处理之前默认不执行
     * <strong>注意 如果 runBeforeUnload 设置为true，可能会弹出一个 beforeunload 对话框。 这个对话框需要通过页面的 'dialog' 事件手动处理</strong>
     *
     * @param runBeforeUnload 默认 false. 是否执行 before unload
     * @throws InterruptedException 异常
     */
    public void close(boolean runBeforeUnload) throws InterruptedException {
        Assert.isTrue(this.client.getConnection() != null, "Protocol error: Connection closed. Most likely the page has been closed.");

        if (runBeforeUnload) {
            this.client.send("Page.close", null, false);
        } else {
            Map<String, Object> params = new HashMap<>();
            params.put("targetId", this.target.getTargetId());
            this.client.getConnection().send("Target.closeTarget", params, true);
            this.target.WaiforisClosedPromise();
        }
    }

    /**
     * 截图
     * 备注 在OS X上 截图需要至少1/6秒。查看讨论：https://crbug.com/741689。
     *
     * @param options 截图选项
     * @return 图片base64的字节
     * @throws IOException 异常
     */
    public String screenshot(ScreenshotOption options) throws IOException {
        String screenshotType = null;
        // options.type takes precedence over inferring the type from options.path
        // because it may be a 0-length file with no extension created beforehand (i.e. as a temp file).
        if (StringKit.isNotEmpty(options.getType())) {
            Assert.isTrue("png".equals(options.getType()) || "jpeg".equals(options.getType()), "Unknown options.type value: " + options.getType());
            screenshotType = options.getType();
        } else if (StringKit.isNotEmpty(options.getPath())) {
            String mimeType = Files.probeContentType(Paths.get(options.getPath()));
            if ("image/png".equals(mimeType))
                screenshotType = "png";
            else if ("image/jpeg".equals(mimeType))
                screenshotType = "jpeg";
            Assert.isTrue(StringKit.isNotEmpty(screenshotType), "Unsupported screenshot mime type: " + mimeType);
        }

        if (StringKit.isEmpty(screenshotType))
            screenshotType = "png";

        if (options.getQuality() > 0) {
            Assert.isTrue("jpeg".equals(screenshotType), "options.quality is unsupported for the " + screenshotType + " screenshots");
            Assert.isTrue(options.getQuality() <= 100, "Expected options.quality to be between 0 and 100 (inclusive), got " + options.getQuality());
        }

        Assert.isTrue(options.getClip() == null || !options.getFullPage(), "options.clip and options.fullPage are exclusive");
        if (options.getClip() != null) {
            Assert.isTrue(options.getClip().getWidth() != 0, "Expected options.clip.width not to be 0.");
            Assert.isTrue(options.getClip().getHeight() != 0, "Expected options.clip.height not to be 0.");
        }

        return (String) this.screenshotTaskQueue.postTask((type, op) -> {
            try {
                return screenshotTask(type, op);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }, screenshotType, options);
    }

    /**
     * 屏幕截图
     *
     * @param path 截图文件全路径
     * @return base64编码后的图片数据
     * @throws IOException 异常
     */
    public String screenshot(String path) throws IOException {
        return this.screenshot(new ScreenshotOption(path));
    }

    /**
     * 当提供的选择器完成选中后，触发change和input事件 如果没有元素匹配指定选择器，将报错。
     *
     * @param selector 要查找的选择器
     * @param values   查找的配置项。如果选择器有多个属性，所有的值都会查找，否则只有第一个元素被找到
     * @return 选择器集合
     */
    public List<String> select(String selector, List<String> values) {
        return this.getMainFrame().select(selector, values);
    }

    /**
     * 返回页面标题
     *
     * @return 页面标题
     */
    public String title() {
        return this.getMainFrame().title();
    }

    /**
     * 设置绕过页面的安全政策
     * 注意 CSP 发生在 CSP 初始化而不是评估阶段。也就是说应该在导航到这个域名前设置
     *
     * @param enabled 是否绕过页面的安全政策
     */
    public void setBypassCSP(boolean enabled) {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", enabled);
        this.client.send("Page.setBypassCSP", params, true);
    }

    /**
     * 设置每个请求忽略缓存。默认是启用缓存的。
     *
     * @param enabled 设置缓存的 enabled 状态
     */
    public void setCacheEnabled(boolean enabled) {
        this.frameManager.getNetworkManager().setCacheEnabled(enabled);
    }

    /**
     * 给页面设置html
     *
     * @param html 分派给页面的HTML。
     */
    public void setContent(String html) {
        this.setContent(html, new NavigateOption());
    }

    /**
     * 给页面设置html
     *
     * @param html    分派给页面的HTML。
     * @param options timeout 加载资源的超时时间，默认值为30秒，传入0禁用超时. 可以使用 page.setDefaultNavigationTimeout(timeout) 或者 page.setDefaultTimeout(timeout) 方法修改默认值
     *                waitUntil  HTML设置成功的标志事件, 默认为 load。 如果给定的是一个事件数组，那么当所有事件之后，给定的内容才被认为设置成功。 事件可以是：
     *                load - load事件触发后，设置HTML内容完成。
     *                domcontentloaded - DOMContentLoaded 事件触发后，设置HTML内容完成。
     *                networkidle0 - 不再有网络连接时（至少500毫秒之后），设置HTML内容完成
     *                networkidle2 - 只剩2个网络连接时（至少500毫秒之后），设置HTML内容完成
     */
    public void setContent(String html, NavigateOption options) {
        this.frameManager.getMainFrame().setContent(html, options);
    }

    /**
     * 获取指定url的cookies
     *
     * @param urls 指定的url集合
     * @return Cookie
     */
    public List<Cookie> cookies(List<String> urls) {
        Map<String, Object> params = new HashMap<>();
        if (urls == null) urls = new ArrayList<>();
        if (urls.size() == 0) urls.add(this.url());
        params.put("urls", urls);
        JSONObject result = this.client.send("Network.getCookies", params, true);
        JSONArray cookiesNode = result.getJSONArray("cookies");
        Iterator<Object> elements = cookiesNode.iterator();
        List<Cookie> cookies = new ArrayList<>();
        while (elements.hasNext()) {
            JSONObject cookieNode = (JSONObject) elements.next();
            Cookie cookie;
            cookie = JSON.toJavaObject(cookieNode, Cookie.class);
            cookie.setPriority(null);
            cookies.add(cookie);

        }
        return cookies;
    }

    /**
     * 返回当前页面的cookies
     *
     * @return cookies
     */
    public List<Cookie> cookies() {
        return this.cookies(null);
    }

    public void setCookie(List<CookieParam> cookies) throws IllegalAccessException, IntrospectionException, InvocationTargetException {
        String pageURL = this.url();
        boolean startsWithHTTP = pageURL.startsWith("http");
        cookies.replaceAll(cookie -> {
            if (StringKit.isEmpty(cookie.getUrl()) && startsWithHTTP)
                cookie.setUrl(pageURL);
            Assert.isTrue(!ABOUT_BLANK.equals(cookie.getUrl()), "Blank page can not have cookie " + cookie.getName());
            if (StringKit.isNotEmpty(cookie.getUrl())) {
                Assert.isTrue(!cookie.getUrl().startsWith("data:"), "Data URL page can not have cookie " + cookie.getName());
            }
            return cookie;
        });
        List<DeleteCookie> deleteCookieParameters = new ArrayList<>();
        for (CookieParam cookie : cookies) {
            deleteCookieParameters.add(new DeleteCookie(cookie.getName(), cookie.getUrl(), cookie.getDomain(), cookie.getPath()));
        }

        this.deleteCookie(deleteCookieParameters);
        Map<String, Object> params = new HashMap<>();
        params.put("cookies", cookies);
        this.client.send("Network.setCookies", params, true);
    }

    /**
     * 此方法会改变下面几个方法的默认30秒等待时间：
     * ${@link Page#goTo(String)}
     * ${@link Page#goTo(String, NavigateOption, boolean)}
     * ${@link Page#goBack(NavigateOption)}
     * ${@link Page#goForward(NavigateOption)}
     * ${@link Page#reload(NavigateOption)}
     * ${@link Page#waitForNavigation()}
     *
     * @param timeout 超时时间
     */
    public void setDefaultNavigationTimeout(int timeout) {
        this.timeout.setDefaultNavigationTimeout(timeout);
    }

    /**
     * 当前页面发起的每个请求都会带上这些请求头
     * 注意 此方法不保证请求头的顺序
     *
     * @param headers 每个 HTTP 请求都会带上这些请求头。值必须是字符串
     */
    public void setExtraHTTPHeaders(Map<String, String> headers) {
        this.frameManager.getNetworkManager().setExtraHTTPHeaders(headers);
    }

    /**
     * Sets the page's geolocation.
     *
     * @param longitude Latitude between -90 and 90.
     * @param latitude  Longitude between -180 and 180.
     * @param accuracy  Optional non-negative accuracy value.
     */
    public void setGeolocation(double longitude, double latitude, int accuracy) {

        if (longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Invalid longitude " + longitude + ": precondition -180 <= LONGITUDE <= 180 failed.");
        if (latitude < -90 || latitude > 90)
            throw new IllegalArgumentException("Invalid latitude " + latitude + ": precondition -90 <= LATITUDE <= 90 failed.");
        if (accuracy < 0)
            throw new IllegalArgumentException("Invalid accuracy " + accuracy + ": precondition 0 <= ACCURACY failed.");
        Map<String, Object> params = new HashMap<>();
        params.put("longitude", longitude);
        params.put("latitude", latitude);
        params.put("accuracy", accuracy);
        this.client.send("Emulation.setGeolocationOverride", params, true);
    }

    /**
     * 设置页面的地理位置
     *
     * @param longitude 纬度 between -90 and 90.
     * @param latitude  经度 between -180 and 180.
     */
    public void setGeolocation(double longitude, double latitude) {
        this.setGeolocation(longitude, latitude, 0);
    }

    /**
     * 是否启用js
     * 注意 改变这个值不会影响已经执行的js。下一个跳转会完全起作用。
     *
     * @param enabled 是否启用js
     */
    public void setJavaScriptEnabled(boolean enabled) {
        if (this.javascriptEnabled == enabled)
            return;
        this.javascriptEnabled = enabled;
        Map<String, Object> params = new HashMap<>();
        params.put("value", !enabled);
        this.client.send("Emulation.setScriptExecutionDisabled", params, true);
    }

    /**
     * 设置启用离线模式。
     *
     * @param enabled 设置 true, 启用离线模式。
     */
    public void setOfflineMode(boolean enabled) {
        this.frameManager.getNetworkManager().setOfflineMode(enabled);
    }

    /**
     * 启用请求拦截器，会激活 request.abort, request.continue 和 request.respond 方法。这提供了修改页面发出的网络请求的功能。
     * 一旦启用请求拦截，每个请求都将停止，除非它继续，响应或中止
     *
     * @param value 是否启用请求拦截器
     */
    public void setRequestInterception(boolean value) {
        this.frameManager.getNetworkManager().setRequestInterception(value);
    }

    /**
     * 如果是一个浏览器多个页面的情况，每个页面都可以有单独的viewport
     * 注意 在大部分情况下，改变 viewport 会重新加载页面以设置 isMobile 或者 hasTouch
     *
     * @param viewport 设置的视图
     */
    public void setViewport(Viewport viewport) {
        boolean needsReload = this.emulationManager.emulateViewport(viewport);
        this.viewport = viewport;
        if (needsReload) this.reload(null);
    }

    /**
     * 返回页面的完整 html 代码，包括 doctype。
     *
     * @return 页面内容
     */
    public String content() {
        return this.frameManager.getMainFrame().content();
    }

    /**
     * 导航到指定的url,可以配置是否阻塞，可以配合下面这个方法使用，但是不限于这个方法
     * {@link Page#waitForResponse(String)}
     * 因为如果不阻塞的话，页面在加载完成时，waitForResponse等waitFor方法会接受不到结果而抛出超时异常
     *
     * @param url     导航的地址
     * @param isBlock true代表阻塞
     * @return 不阻塞的话返回null
     * @throws InterruptedException 打断异常
     */
    public Response goTo(String url, boolean isBlock) throws InterruptedException {
        return this.goTo(url, new NavigateOption(), isBlock);
    }

    /**
     * 导航到指定的url,因为goto是java的关键字，所以就采用了goTo方法名
     * <p>
     * 以下情况此方法将报错：
     * 发生了 SSL 错误 (比如有些自签名的https证书).
     * 目标地址无效
     * 超时
     * 主页面不能加载
     *
     * @param url      url
     * @param options: timeout 跳转等待时间，单位是毫秒, 默认是30秒, 传 0 表示无限等待。可以通过page.setDefaultNavigationTimeout(timeout)方法修改默认值
     *                 waitUntil  满足什么条件认为页面跳转完成，默认是 load 事件触发时。指定事件数组，那么所有事件触发后才认为是跳转完成。事件包括：
     *                 load - 页面的load事件触发时
     *                 domcontentloaded - 页面的 DOMContentLoaded 事件触发时
     *                 networkidle0 - 不再有网络连接时触发（至少500毫秒后）
     *                 networkidle2 - 只有2个网络连接时触发（至少500毫秒后）
     *                 referer  Referer header value. If provided it will take preference over the referer header value set by page.setExtraHTTPHeaders().
     * @return Response
     * @throws InterruptedException 异常
     */
    public Response goTo(String url, NavigateOption options) throws InterruptedException {
        return this.goTo(url, options, true);
    }

    /**
     * 导航到指定的url,因为goto是java的关键字，所以就采用了goTo方法名
     * 以下情况此方法将报错：
     * 发生了 SSL 错误 (比如有些自签名的https证书).
     * 目标地址无效
     * 超时
     * 主页面不能加载
     *
     * @param url      url
     * @param options: timeout 跳转等待时间，单位是毫秒, 默认是30秒, 传 0 表示无限等待。可以通过page.setDefaultNavigationTimeout(timeout)方法修改默认值
     *                 waitUntil  满足什么条件认为页面跳转完成，默认是 load 事件触发时。指定事件数组，那么所有事件触发后才认为是跳转完成。事件包括：
     *                 load - 页面的load事件触发时
     *                 domcontentloaded - 页面的 DOMContentLoaded 事件触发时
     *                 networkidle0 - 不再有网络连接时触发（至少500毫秒后）
     *                 networkidle2 - 只有2个网络连接时触发（至少500毫秒后）
     *                 referer  Referer header value. If provided it will take preference over the referer header value set by page.setExtraHTTPHeaders().
     * @param isBlock  是否阻塞，不阻塞代表只是发导航命令出去，并不等待导航结果，同时也不会抛异常
     * @return Response
     * @throws InterruptedException 打断异常
     */
    public Response goTo(String url, NavigateOption options, boolean isBlock) throws InterruptedException {
        return this.frameManager.getMainFrame().goTo(url, options, isBlock);
    }

    /**
     * 导航到某个网站
     * 以下情况此方法将报错：
     * 发生了 SSL 错误 (比如有些自签名的https证书).
     * 目标地址无效
     * 超时
     * 主页面不能加载
     *
     * @param url 导航到的地址. 地址应该带有http协议, 比如 https://.
     * @return 响应
     * @throws InterruptedException 打断异常
     */
    public Response goTo(String url) throws InterruptedException {
        return this.goTo(url, true);
    }

    /**
     * 删除cookies
     *
     * @param cookies 指定删除的cookies
     * @throws IllegalAccessException    异常
     * @throws IntrospectionException    异常
     * @throws InvocationTargetException 异常
     */
    public void deleteCookie(List<DeleteCookie> cookies) throws IllegalAccessException, IntrospectionException, InvocationTargetException {
        String pageURL = this.url();
        for (DeleteCookie cookie : cookies) {
            if (StringKit.isEmpty(cookie.getUrl()) && pageURL.startsWith("http"))
                cookie.setUrl(pageURL);
            Map<String, Object> params = getProperties(cookie);
            this.client.send("Network.deleteCookies", params, true);
        }
    }

    /**
     * 根据指定的参数和 user agent 生成模拟器。此方法是和下面两个方法效果相同
     * ${@link Page#setViewport(Viewport)}
     * ${@link Page#setUserAgent(String)}
     *
     * @param options Device 模拟器枚举类
     * @throws InterruptedException 线程被打断异常
     * @throws ExecutionException   并发异常
     */
    public void emulate(Device options) throws ExecutionException, InterruptedException {
        this.setViewport(options.getViewport());
        this.setUserAgent(options.getUserAgent());
    }

    /**
     * 给页面设置userAgent
     *
     * @param userAgent userAgent的值
     */
    public void setUserAgent(String userAgent) {
        this.frameManager.getNetworkManager().setUserAgent(userAgent);
    }

    /**
     * 改变页面的css媒体类型。支持的值仅包括 'screen', 'print' 和 null。传 null 禁用媒体模拟
     *
     * @param type css媒体类型
     */
    public void emulateMediaType(String type) {
        this.emulateMedia(type);
    }

    /**
     * 此方法找到一个匹配的元素，如果需要会把此元素滚动到可视，然后通过 page.touchscreen 来点击元素的中间位置 如果没有匹配的元素，此方法会报错
     *
     * @param selector 要点击的元素的选择器。如果有多个匹配的元素，点击第一个
     * @param isBlock  是否阻塞，如果是false,那么将在另外的线程中完成，可以配合waitFor方法
     */
    public void tap(String selector, boolean isBlock) {
        this.getMainFrame().tap(selector, isBlock);
    }

    /**
     * 此方法找到一个匹配的元素，如果需要会把此元素滚动到可视，然后通过 page.touchscreen 来点击元素的中间位置 如果没有匹配的元素，此方法会报错
     *
     * @param selector 要点击的元素的选择器。如果有多个匹配的元素，点击第一个
     */
    public void tap(String selector) {
        this.tap(selector, true);
    }

    /**
     * 更改页面的时区，传null将禁用将时区仿真
     * <a href="https://cs.chromium.org/chromium/src/third_party/icu/source/data/misc/metaZones.txt?rcl=faee8bc70570192d82d2978a71e2a615788597d1">时区id列表</a>
     *
     * @param timezoneId 时区id
     */
    public void emulateTimezone(String timezoneId) {
        try {
            Map<String, Object> params = new HashMap<>();
            if (timezoneId == null) {
                timezoneId = Normal.EMPTY;
            }
            params.put("timezoneId", timezoneId);
            this.client.send("Emulation.setTimezoneOverride", params, true);
        } catch (Exception e) {
            if (e.getMessage().contains("Invalid timezone"))
                throw new IllegalArgumentException("Invalid timezone ID: " + timezoneId);
            throw e;
        }
    }

    /**
     * 模拟页面上给定的视力障碍,不同视力障碍，截图有不同效果
     *
     * @param type 视力障碍类型
     */
    public void emulateVisionDeficiency(Variables.VisionDeficiency type) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type.getValue());
        this.client.send("Emulation.setEmulatedVisionDeficiency", params, true);
    }

    /**
     * 此方法是{@link Page#evaluateOnNewDocument(String, Object...)}的简化版，自动判断参数pageFunction是 Javascript 函数还是 Javascript 的字符串
     *
     * @param pageFunction 要执行的字符串
     * @param args         如果是 Javascript 函数的话，对应函数上的参数
     */
    public void evaluateOnNewDocument(String pageFunction, Object... args) {
        this.evaluateOnNewDocument(pageFunction, Builder.isFunction(pageFunction) ? Variables.PageEvaluateType.FUNCTION : Variables.PageEvaluateType.STRING, args);
    }

    /**
     * 在新dom产生之际执行给定的javascript
     * 当你的js代码为函数时，type={@link Variables.PageEvaluateType#FUNCTION}
     * 当你的js代码为字符串时，type={@link Variables.PageEvaluateType#STRING}
     *
     * @param pageFunction js代码
     * @param type         一般为PageEvaluateType#FUNCTION
     * @param args         当你js代码是函数时，你的函数的参数
     */
    public void evaluateOnNewDocument(String pageFunction, Variables.PageEvaluateType type, Object... args) {
        Map<String, Object> params = new HashMap<>();
        if (Objects.equals(Variables.PageEvaluateType.STRING, type)) {
            Assert.isTrue(args.length == 0, "Cannot evaluate a string with arguments");
            params.put("source", pageFunction);
        } else {
            List<Object> objects = Arrays.asList(args);
            List<String> argsList = new ArrayList<>();
            objects.forEach(arg -> {
                if (arg == null) {
                    argsList.add("undefined");
                } else {
                    argsList.add(JSON.toJSONString(arg));
                }
            });
            String source = "(" + pageFunction + ")(" + String.join(",", argsList) + ")";
            params.put("source", source);
        }
        this.client.send("Page.addScriptToEvaluateOnNewDocument", params, true);
    }

    /**
     * 此方法添加一个命名为 name 的方法到页面的 window 对象 当调用 name 方法时，在 node.js 中执行 puppeteerFunction
     *
     * @param name              挂载到window对象的方法名
     * @param puppeteerFunction 调用name方法时实际执行的方法
     * @throws ExecutionException   异常
     * @throws InterruptedException 异常
     */
    public void exposeFunction(String name, Function<List<?>, Object> puppeteerFunction) throws InterruptedException, ExecutionException {
        if (this.pageBindings.containsKey(name)) {
            throw new IllegalArgumentException(MessageFormat.format("Failed to add page binding with name {0}: window['{1}'] already exists!", name, name));
        }
        this.pageBindings.put(name, puppeteerFunction);
        String expression = Builder.evaluationString(addPageBinding(), Variables.PageEvaluateType.FUNCTION, name);
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        this.client.send("Runtime.addBinding", params, true);
        params.clear();
        params.put("source", expression);
        this.client.send("Page.addScriptToEvaluateOnNewDocument", params, true);
        List<Frame> frames = this.frames();
        if (frames.isEmpty()) {
            return;
        }

        CompletionService completionService = new ExecutorCompletionService(Builder.commonExecutor());
        frames.forEach(frame -> completionService.submit(() -> frame.evaluate(expression, null)));
        for (int i = 0; i < frames.size(); i++) {
            completionService.take().get();
        }
    }

    /**
     * 此方法找到一个匹配selector的元素，并且把焦点给它。 如果没有匹配的元素，此方法将报错。
     *
     * @param selector 要给焦点的元素的选择器selector。如果有多个匹配的元素，焦点给第一个元素。
     */
    public void focus(String selector) {
        this.getMainFrame().focus(selector);
    }

    /**
     * 返回加载到页面中的所有iframe标签
     *
     * @return iframe标签
     */
    public List<Frame> frames() {
        return this.frameManager.frames();
    }

    public Response goBack() {
        return this.go(-1, new NavigateOption());
    }

    /**
     * 导航到页面历史的前一个页面
     * options 的 referer参数不用填，填了也用不上
     * <p>
     * options 导航配置，可选值：
     * otimeout  跳转等待时间，单位是毫秒, 默认是30秒, 传 0 表示无限等待。可以通过page.setDefaultNavigationTimeout(timeout)方法修改默认值
     * owaitUntil 满足什么条件认为页面跳转完成，默认是load事件触发时。指定事件数组，那么所有事件触发后才认为是跳转完成。事件包括：
     * oload - 页面的load事件触发时
     * odomcontentloaded - 页面的DOMContentLoaded事件触发时
     * onetworkidle0 - 不再有网络连接时触发（至少500毫秒后）
     * onetworkidle2 - 只有2个网络连接时触发（至少500毫秒后）
     *
     * @param options 见上面注释
     * @return 响应
     */
    public Response goBack(NavigateOption options) {
        return this.go(-1, options);
    }

    public Response goForward() {
        return this.go(+1, new NavigateOption());
    }

    /**
     * 导航到页面历史的后一个页面。
     * options 的 referer参数不用填，填了也用不上
     *
     * @param options 可以看{@link Page#goTo(String, NavigateOption, boolean)}方法介绍
     * @return Response 响应
     */
    public Response goForward(NavigateOption options) {
        return this.go(+1, options);
    }

    /**
     * 此方法找到一个匹配的元素，如果需要会把此元素滚动到可视，然后通过 page.mouse 来hover到元素的中间。 如果没有匹配的元素，此方法将会报错。
     *
     * @param selector 要hover的元素的选择器。如果有多个匹配的元素，hover第一个。
     */
    public void hover(String selector) {
        this.getMainFrame().hover(selector);
    }

    /**
     * 表示页面是否被关闭。
     *
     * @return 页面是否被关闭。
     */
    public boolean isClosed() {
        return this.closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    /**
     * 返回页面的一些基本信息
     *
     * @return Metrics 基本信息载体
     * @throws IllegalAccessException    异常
     * @throws IntrospectionException    异常
     * @throws InvocationTargetException 异常
     */
    public Metrics metrics() throws IllegalAccessException, IntrospectionException, InvocationTargetException {
        JSONObject responseNode = this.client.send("Performance.getMetrics", null, true);
        List<Metric> metrics = new ArrayList<>();
        List<JSONObject> list = responseNode.getObject("metrics", new TypeReference<List<JSONObject>>() {
        });
        for (JSONObject next : list) {
            Metric value = JSON.toJavaObject(next, Metric.class);
            metrics.add(value);

        }
        return this.buildMetricsObject(metrics);
    }

    /**
     * 生成当前页面的pdf格式，带着 pring css media。如果要生成带着 screen media的pdf，在page.pdf() 前面先调用 page.emulateMedia('screen')
     * <strong>注意 目前仅支持无头模式的 Chrome</strong>
     *
     * @param path pdf存放的路径
     * @throws IOException 异常
     */
    public void pdf(String path) throws IOException {
        this.pdf(new PDFOption(path));
    }

    /**
     * 生成当前页面的pdf格式，带着 pring css media。如果要生成带着 screen media的pdf，在page.pdf() 前面先调用 page.emulateMedia('screen')
     * <strong>注意 目前仅支持无头模式的 Chrome</strong>
     *
     * @param options 选项
     * @return pdf文件的字节数组数据
     * @throws IOException 异常
     */
    public byte[] pdf(PDFOption options) throws IOException {
        double paperWidth = 8.5;
        double paperHeight = 11;

        if (StringKit.isNotEmpty(options.getFormat())) {
            Variables.Paper format = Variables.Paper.valueOf(options.getFormat().toLowerCase());
            paperWidth = format.getWidth();
            paperHeight = format.getHeight();
        } else {
            Double width = convertPrintParameterToInches(options.getWidth());
            if (width != null) {
                paperWidth = width;
            }
            Double height = convertPrintParameterToInches(options.getHeight());
            if (height != null) {
                paperHeight = height;
            }
        }

        Margin margin = options.getMargin();
        Number marginTop, marginLeft, marginBottom, marginRight;

        if ((marginTop = convertPrintParameterToInches(margin.getTop())) == null) {
            marginTop = 0;
        }

        if ((marginLeft = convertPrintParameterToInches(margin.getLeft())) == null) {
            marginLeft = 0;
        }

        if ((marginBottom = convertPrintParameterToInches(margin.getBottom())) == null) {
            marginBottom = 0;
        }

        if ((marginRight = convertPrintParameterToInches(margin.getRight())) == null) {
            marginRight = 0;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("transferMode", "ReturnAsStream");
        params.put("landscape", options.getLandscape());
        params.put("displayHeaderFooter", options.getDisplayHeaderFooter());
        params.put("headerTemplate", options.getHeaderTemplate());
        params.put("footerTemplate", options.getFooterTemplate());
        params.put("printBackground", options.getPrintBackground());
        params.put("scale", options.getScale());
        params.put("paperWidth", paperWidth);
        params.put("paperHeight", paperHeight);
        params.put("marginTop", marginTop);
        params.put("marginBottom", marginBottom);
        params.put("marginLeft", marginLeft);
        params.put("marginRight", marginRight);
        params.put("pageRanges", options.getPageRanges());
        params.put("preferCSSPageSize", options.getPreferCSSPageSize());
        JSONObject result = this.client.send("Page.printToPDF", params, true);

        if (result != null) {
            String handle = result.getString(Variables.RECV_MESSAGE_STREAM_PROPERTY);
            Assert.isTrue(handle != null, "Page.printToPDF result has no stream handle. Please check your chrome version. result=" + result.toString());
            return (byte[]) Builder.readProtocolStream(this.client, handle, options.getPath(), false);
        }
        throw new InstrumentException("Page.printToPDF no response");
    }

    /**
     * 此方法会改变下面几个方法的默认30秒等待时间：
     * ${@link Page#goTo(String)}
     * ${@link Page#goTo(String, NavigateOption, boolean)}
     * ${@link Page#goBack(NavigateOption)}
     * ${@link Page#goForward(NavigateOption)}
     * ${@link Page#reload(NavigateOption)}
     * ${@link Page#waitForNavigation()}
     *
     * @param timeout 超时时间
     */
    public void setDefaultTimeout(int timeout) {
        this.timeout.setDefaultTimeout(timeout);
    }

    /**
     * 此方法遍历js堆栈，找到所有带有指定原型的对象
     *
     * @param prototypeHandle 原型处理器
     * @return 代表页面元素的一个实例
     */
    public JSHandle queryObjects(JSHandle prototypeHandle) {
        ExecutionContext context = this.getMainFrame().executionContext();
        return context.queryObjects(prototypeHandle);
    }

    /**
     * 重新加载页面
     *
     * @param options 与${@link Page#goTo(String, NavigateOption, boolean)}中的options是一样的配置
     * @return 响应
     */
    public Response reload(NavigateOption options) {
        CountDownLatch reloadLatch = new CountDownLatch(1);
        Page.reloadExecutor.submit(() -> {
            /*执行reload命令，不用等待返回*/
            try {
                reloadLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            this.client.send("Page.reload", null, true);
        });

        // 等待页面导航结果返回
        return this.waitForNavigation(options, reloadLatch);
    }

    /**
     * 此方法在页面跳转到一个新地址或重新加载时解析，如果你的代码会间接引起页面跳转，这个方法比较有用
     * 比如你在在代码中使用了Page.click()方法，引起了页面跳转
     * 注意 通过 History API 改变地址会认为是一次跳转。
     *
     * @return 响应
     */
    public Response waitForNavigation() {
        return this.waitForNavigation(new NavigateOption(), null);
    }

    /**
     * 此方法在页面跳转到一个新地址或重新加载时解析，如果你的代码会间接引起页面跳转，这个方法比较有用
     * 比如你在在代码中使用了Page.click()方法，引起了页面跳转
     * 注意 通过 History API 改变地址会认为是一次跳转。
     *
     * @param options PageNavigateOptions
     * @return 响应
     */
    public Response waitForNavigation(NavigateOption options) {
        return this.frameManager.getMainFrame().waitForNavigation(options, null);
    }

    /**
     * 执行一段 JavaScript代码
     * 此方法是{@link Page#evaluate(String, List)}的简化版，自动判断参数pageFunction是 Javascript 函数还是 Javascript 的字符串
     *
     * @param pageFunction 要执行的字符串
     * @return 有可能是JShandle String等
     */
    public Object evaluate(String pageFunction) {
        return this.evaluate(pageFunction, new ArrayList<>());
    }

    /**
     * 执行一段 JavaScript代码
     *
     * @param pageFunction 要执行的字符串
     * @param args         如果是 Javascript 函数的话，对应函数上的参数
     * @return 有可能是JShandle String等
     */
    public Object evaluate(String pageFunction, List<Object> args) {
        return this.getMainFrame().evaluate(pageFunction, args);
    }

    /**
     * 此方法和 page.evaluate 的唯一区别是此方法返回的是页内类型(JSHandle)
     * 此方法是{@link Page#evaluateHandle(String, List)}的简化版，自动判断参数pageFunction是 Javascript 函数还是 Javascript 的字符串
     *
     * @param pageFunction 要执行的字符串
     * @return JSHandle
     */
    public JSHandle evaluateHandle(String pageFunction) {
        return this.evaluateHandle(pageFunction, new ArrayList<>());
    }

    /**
     * 改变页面的css媒体类型。支持的值仅包括 'screen', 'print' 和 null。传 null 禁用媒体模拟
     *
     * @param type css媒体类型
     */
    public void emulateMedia(String type) {
        Assert.isTrue("screen".equals(type) || "print".equals(type) || type == null, "Unsupported media type: " + type);
        Map<String, Object> params = new HashMap<>();
        params.put("media", type);
        this.client.send("Emulation.setEmulatedMedia", params, true);
    }

    public void emulateMediaFeatures(List<MediaFeature> features) {
        Pattern pattern = Pattern.compile("^prefers-(?:color-scheme|reduced-motion)$");
        Map<String, Object> params = new HashMap<>();
        if (features == null) {
            params.put("features", null);
            this.client.send("Emulation.setEmulatedMedia", params, true);
        }
        if (CollKit.isNotEmpty(features)) {
            features.forEach(mediaFeature -> {
                String name = mediaFeature.getName();
                Assert.isTrue(pattern.matcher(name).find(), "Unsupported media feature: " + name);
            });
        }
        params.put("features", features);
        this.client.send("Emulation.setEmulatedMedia", params, true);
    }

    /**
     * 此方法根据第一个参数的不同有不同的结果：
     * <p>
     * 如果 selectorOrFunctionOrTimeout 是 string, 那么认为是 css 选择器或者一个xpath, 根据是不是'//'开头, 这时候此方法是 page.waitForSelector 或 page.waitForXPath的简写
     * 如果 selectorOrFunctionOrTimeout 是 function, 那么认为是一个predicate，这时候此方法是page.waitForFunction()的简写
     * 如果 selectorOrFunctionOrTimeout 是 number, 那么认为是超时时间，单位是毫秒，返回的是Promise对象,在指定时间后resolve
     * 否则会报错
     *
     * @param selectorOrFunctionOrTimeout 选择器, 方法 或者 超时时间
     * @return 代表页面元素的一个实例
     * @throws InterruptedException 打断异常
     */
    public JSHandle waitFor(String selectorOrFunctionOrTimeout) throws InterruptedException {
        return this.waitFor(selectorOrFunctionOrTimeout, new WaitForOption(), new ArrayList<>());
    }

    /**
     * 此方法根据第一个参数的不同有不同的结果：
     * <p>
     * 如果 selectorOrFunctionOrTimeout 是 string, 那么认为是 css 选择器或者一个xpath, 根据是不是'//'开头, 这时候此方法是 page.waitForSelector 或 page.waitForXPath的简写
     * 如果 selectorOrFunctionOrTimeout 是 function, 那么认为是一个predicate，这时候此方法是page.waitForFunction()的简写
     * 如果 selectorOrFunctionOrTimeout 是 number, 那么认为是超时时间，单位是毫秒，返回的是Promise对象,在指定时间后resolve
     * 否则会报错
     *
     * @param selectorOrFunctionOrTimeout 选择器, 方法 或者 超时时间
     * @param options                     可选的等待参数
     * @param args                        传给 pageFunction 的参数
     * @return 代表页面元素的一个实例
     * @throws InterruptedException 打断异常
     */
    public JSHandle waitFor(String selectorOrFunctionOrTimeout, WaitForOption options, List<Object> args) throws InterruptedException {
        return this.getMainFrame().waitFor(selectorOrFunctionOrTimeout, options, args);
    }

    /**
     * 等待一个文件选择事件，默认等待时间是30s
     *
     * @return 文件选择器
     */
    public Future<FileChooser> waitForFileChooser() {
        return this.waitForFileChooser(this.timeout.timeout());
    }

    /**
     * 等待一个文件选择事件，默认等待时间是30s
     *
     * @param timeout 等待时间
     * @return 文件选择器
     */
    public Future<FileChooser> waitForFileChooser(int timeout) {
        if (timeout <= 0)
            timeout = this.timeout.timeout();
        int finalTimeout = timeout;
        return Builder.commonExecutor().submit(() -> {
            if (CollKit.isEmpty(this.fileChooserInterceptors)) {
                Map<String, Object> params = new HashMap<>();
                params.put("enabled", true);
                this.client.send("Page.setInterceptFileChooserDialog", params, true);
            }
            CountDownLatch latch = new CountDownLatch(1);
            FileChooserCallBack callback = new FileChooserCallBack(latch);
            this.fileChooserInterceptors.add(callback);
            try {
                callback.waitForFileChooser(finalTimeout);
                return callback.getFileChooser();
            } catch (InterruptedException e) {
                this.fileChooserInterceptors.remove(callback);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 要在浏览器实例上下文执行方法
     *
     * @param pageFunction 要在浏览器实例上下文执行的方法
     * @return JSHandle
     * @throws InterruptedException 打断异常
     */
    public JSHandle waitForFunction(String pageFunction) throws InterruptedException {
        return this.waitForFunction(pageFunction, new WaitForOption());
    }

    /**
     * 要在浏览器实例上下文执行方法
     *
     * @param pageFunction 要在浏览器实例上下文执行的方法
     * @param args         js函数的参数
     * @return JSHandle 指定的页面元素 对象
     * @throws InterruptedException 异常
     */
    public JSHandle waitForFunction(String pageFunction, List<Object> args) throws InterruptedException {
        return this.waitForFunction(pageFunction, new WaitForOption(), args);
    }

    /**
     * 要在浏览器实例上下文执行方法
     *
     * @param pageFunction 要在浏览器实例上下文执行的方法
     * @param options      可选参数
     * @return JSHandle
     * @throws InterruptedException 异常
     */
    public JSHandle waitForFunction(String pageFunction, WaitForOption options) throws InterruptedException {
        return this.waitForFunction(pageFunction, options, new ArrayList<>());
    }

    /**
     * 要在浏览器实例上下文执行方法
     *
     * @param pageFunction 要在浏览器实例上下文执行的方法
     * @param options      可选参数
     * @param args         执行的方法的参数
     * @return JSHandle
     * @throws InterruptedException 异常
     */
    public JSHandle waitForFunction(String pageFunction, WaitForOption options, List<Object> args) throws InterruptedException {
        return this.getMainFrame().waitForFunction(pageFunction, options, args);
    }

    /**
     * 等到某个请求
     *
     * @param predicate 等待的请求
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Request waitForRequest(Predicate<Request> predicate) throws InterruptedException {
        Assert.notNull(predicate, "waitForRequest predicate must not be null");
        return this.waitForRequest(null, predicate, this.timeout.timeout());
    }

    /**
     * 等到某个请求，url或者predicate只有有一个不为空,默认等待时间是30s
     *
     * @param url 等待的请求
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Request waitForRequest(String url) throws InterruptedException {
        Assert.isTrue(StringKit.isNotEmpty(url), "waitForRequest url must not be empty");
        return this.waitForRequest(url, null, this.timeout.timeout());
    }

    /**
     * 等到某个请求，url或者predicate只有有一个不为空
     * 当url不为空时， type = PageEvaluateType.STRING
     * 当predicate不为空时， type = PageEvaluateType.FUNCTION
     *
     * @param url       等待的请求
     * @param predicate 方法
     * @param timeout   超时时间
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Request waitForRequest(String url, Predicate<Request> predicate, int timeout) throws InterruptedException {
        if (timeout <= 0) {
            timeout = this.timeout.timeout();
        }
        Predicate<Request> predi = request -> {
            if (StringKit.isNotEmpty(url)) {
                return url.equals(request.url());
            } else if (predicate != null) {
                return predicate.test(request);
            }
            return false;
        };
        BrowserListener<Object> listener = null;
        try {
            listener = sessionClosePromise();
            return (Request) Builder.waitForEvent(this.frameManager.getNetworkManager(), Variables.Event.NETWORK_MANAGER_REQUEST.getName(), predi, timeout, "Wait for request timeout");
        } finally {
            if (listener != null)
                this.client.removeListener(Variables.Event.CDPSESSION_DISCONNECTED.getName(), listener);
        }
    }

    /**
     * 等到某个请求,默认等待的时间是30s
     *
     * @param predicate 判断具体某个请求
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Response waitForResponse(Predicate<Response> predicate) throws InterruptedException {
        return this.waitForResponse(null, predicate);
    }

    /**
     * 等到某个请求,默认等待的时间是30s
     *
     * @param url 等待的请求
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Response waitForResponse(String url) throws InterruptedException {
        return this.waitForResponse(url, null);
    }

    /**
     * 等到某个请求，url或者predicate只有有一个不为空,默认等待的时间是30s
     * 当url不为空时， type = PageEvaluateType.STRING
     * 当predicate不为空时， type = PageEvaluateType.FUNCTION
     *
     * @param url       等待的请求
     * @param predicate 方法
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Response waitForResponse(String url, Predicate<Response> predicate) throws InterruptedException {
        return this.waitForResponse(url, predicate, this.timeout.timeout());
    }

    /**
     * 等到某个请求，url或者predicate只有有一个不为空
     * 当url不为空时， type = PageEvaluateType.STRING
     * 当predicate不为空时， type = PageEvaluateType.FUNCTION
     *
     * @param url       等待的请求
     * @param predicate 方法
     * @param timeout   超时时间
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Response waitForResponse(String url, Predicate<Response> predicate, int timeout) throws InterruptedException {
        if (timeout <= 0)
            timeout = this.timeout.timeout();
        Predicate<Response> predi = response -> {
            if (StringKit.isNotEmpty(url)) {
                return url.equals(response.url());
            } else if (predicate != null) {
                return predicate.test(response);
            }
            return false;
        };
        BrowserListener<Object> listener = null;
        try {
            listener = sessionClosePromise();
            return (Response) Builder.waitForEvent(this.frameManager.getNetworkManager(), Variables.Event.NETWORK_MANAGER_RESPONSE.getName(), predi, timeout, "Wait for response timeout");
        } finally {
            if (listener != null)
                this.client.removeListener(Variables.Event.CDPSESSION_DISCONNECTED.getName(), listener);
        }
    }

    /**
     * 等待指定的选择器匹配的元素出现在页面中，如果调用此方法时已经有匹配的元素，那么此方法立即返回。 如果指定的选择器在超时时间后扔不出现，此方法会报错。
     *
     * @param selector 要等待的元素选择器
     * @return ElementHandle
     * @throws InterruptedException 打断异常
     */
    public ElementHandle waitForSelector(String selector) throws InterruptedException {
        return this.waitForSelector(selector, new WaitForOption());
    }

    /**
     * 等待指定的选择器匹配的元素出现在页面中，如果调用此方法时已经有匹配的元素，那么此方法立即返回。 如果指定的选择器在超时时间后扔不出现，此方法会报错。
     *
     * @param selector 要等待的元素选择器
     * @param options  可选参数
     * @return ElementHandle
     * @throws InterruptedException 打断异常
     */
    public ElementHandle waitForSelector(String selector, WaitForOption options) throws InterruptedException {
        return this.getMainFrame().waitForSelector(selector, options);
    }

    /**
     * 等待指定的xpath匹配的元素出现在页面中，如果调用此方法时已经有匹配的元素，那么此方法立即返回。 如果指定的xpath在超时时间后扔不出现，此方法会报错。
     *
     * @param xpath 要等待的元素的xpath
     * @return JSHandle
     * @throws InterruptedException 打断异常
     */
    public JSHandle waitForXPath(String xpath) throws InterruptedException {
        return this.getMainFrame().waitForXPath(xpath, new WaitForOption());
    }

    /**
     * 等待指定的xpath匹配的元素出现在页面中，如果调用此方法时已经有匹配的元素，那么此方法立即返回。 如果指定的xpath在超时时间后扔不出现，此方法会报错。
     *
     * @param xpath   要等待的元素的xpath
     * @param options 可选参数
     * @return JSHandle
     * @throws InterruptedException 打断异常
     */
    public JSHandle waitForXPath(String xpath, WaitForOption options) throws InterruptedException {
        return this.getMainFrame().waitForXPath(xpath, options);
    }

    /**
     * 该方法返回所有与页面关联的 WebWorkers
     *
     * @return WebWorkers
     */
    public Map<String, Worker> workers() {
        return this.workers;
    }

    public Mouse mouse() {
        return mouse;
    }

    public Target target() {
        return this.target;
    }

    public Touchscreen touchscreen() {
        return this.touchscreen;
    }

    public Tracing tracing() {
        return this.tracing;
    }

    public Accessibility accessibility() {
        return this.accessibility;
    }

    /**
     * 每个字符输入后都会触发 keydown, keypress/input 和 keyup 事件
     * 要点击特殊按键，比如 Control 或 ArrowDown，用 keyboard.press
     *
     * @param selector 要输入内容的元素选择器。如果有多个匹配的元素，输入到第一个匹配的元素。
     * @param text     要输入的内容
     * @throws InterruptedException 异常
     */
    public void type(String selector, String text) throws InterruptedException {
        this.getMainFrame().type(selector, text, 0);
    }

    /**
     * 每个字符输入后都会触发 keydown, keypress/input 和 keyup 事件
     * 要点击特殊按键，比如 Control 或 ArrowDown，用 keyboard.press
     *
     * @param selector 要输入内容的元素选择器。如果有多个匹配的元素，输入到第一个匹配的元素。
     * @param text     要输入的内容
     * @param delay    每个字符输入的延迟，单位是毫秒。默认是 0。
     * @throws InterruptedException 异常
     */
    public void type(String selector, String text, int delay) throws InterruptedException {
        this.getMainFrame().type(selector, text, delay);
    }

    public boolean getJavascriptEnabled() {
        return javascriptEnabled;
    }


    public Keyboard keyboard() {
        return this.keyboard;
    }

    /**
     * 获取Viewport,Viewport各个参数的含义：
     * width 宽度，单位是像素
     * height  高度，单位是像素
     * deviceScaleFactor  定义设备缩放， (类似于 dpr)。 默认 1。
     * isMobile  要不要包含meta viewport 标签。 默认 false。
     * hasTouch 指定终端是否支持触摸。 默认 false
     * isLandscape 指定终端是不是 landscape 模式。 默认 false。
     *
     * @return Viewport
     */
    public Viewport viewport() {
        return this.viewport;
    }

    public Coverage coverage() {
        return this.coverage;
    }

    protected CDPSession client() {
        return client;
    }

    protected void initialize() {
        frameManager.initialize();
        Map<String, Object> params = new HashMap<>();
        params.put("autoAttach", true);
        params.put("waitForDebuggerOnStart", false);
        params.put("flatten", true);
        this.client.send("Target.setAutoAttach", params, false);
        params.clear();
        this.client.send("Performance.enable", params, false);
        this.client.send("Log.enable", params, true);
    }

    private Map<String, Object> getProperties(DeleteCookie cookie) throws IntrospectionException, InvocationTargetException, IllegalAccessException {
        Map<String, Object> params = new HashMap<>();
        BeanInfo beanInfo = Introspector.getBeanInfo(cookie.getClass());
        PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
        for (PropertyDescriptor descriptor : propertyDescriptors) {
            params.put(descriptor.getName(), descriptor.getReadMethod().invoke(cookie));
        }
        return params;
    }

    private String addPageBinding() {
        return "function addPageBinding(bindingName) {\n" +
                "      const win = (window);\n" +
                "      const binding = (win[bindingName]);\n" +
                "      win[bindingName] = (...args) => {\n" +
                "        const me = window[bindingName];\n" +
                "        let callbacks = me['callbacks'];\n" +
                "        if (!callbacks) {\n" +
                "          callbacks = new Map();\n" +
                "          me['callbacks'] = callbacks;\n" +
                "        }\n" +
                "        const seq = (me['lastSeq'] || 0) + 1;\n" +
                "        me['lastSeq'] = seq;\n" +
                "        const promise = new Promise((resolve, reject) => callbacks.set(seq, {resolve, reject}));\n" +
                "        binding(JSON.stringify({name: bindingName, seq, args}));\n" +
                "        return promise;\n" +
                "      };\n" +
                "    }";
    }

    private Double convertPrintParameterToInches(String parameter) {
        if (StringKit.isEmpty(parameter)) {
            return null;
        }
        double pixels;
        if (Builder.isNumber(parameter)) {
            pixels = Double.parseDouble(parameter);
        } else if (parameter.endsWith("px") || parameter.endsWith("in") || parameter.endsWith("cm") || parameter.endsWith("mm")) {

            String unit = parameter.substring(parameter.length() - 2).toLowerCase();
            String valueText;
            if (unitToPixels.containsKey(unit)) {
                valueText = parameter.substring(0, parameter.length() - 2);
            } else {
                unit = "px";
                valueText = parameter;
            }
            double value = Double.parseDouble(valueText);
            Assert.isTrue(!Double.isNaN(value), "Failed to parse parameter value: " + parameter);
            pixels = value * unitToPixels.get(unit);
        } else {
            throw new IllegalArgumentException("page.pdf() Cannot handle parameter type: " + parameter);
        }
        return pixels / 96;
    }

    /**
     * 此方法在页面跳转到一个新地址或重新加载时解析，如果你的代码会间接引起页面跳转，这个方法比较有用
     * 比如你在在代码中使用了Page.click()方法，引起了页面跳转
     * 注意 通过 History API 改变地址会认为是一次跳转。
     *
     * @param options     PageNavigateOptions
     * @param reloadLatch reload页面，这个参数配合{@link Page#setViewport(Viewport)}中的reload方法使用
     * @return 响应
     */
    private Response waitForNavigation(NavigateOption options, CountDownLatch reloadLatch) {
        return this.frameManager.getMainFrame().waitForNavigation(options, reloadLatch);
    }

    /**
     * 此方法和 page.evaluate 的唯一区别是此方法返回的是页内类型(JSHandle)
     *
     * @param pageFunction 要在页面实例上下文中执行的方法
     * @param args         要在页面实例上下文中执行的方法的参数
     * @return 代表页面元素的实例
     */
    private JSHandle evaluateHandle(String pageFunction, List<Object> args) {
        ExecutionContext context = this.getMainFrame().executionContext();
        return (JSHandle) context.evaluateHandle(pageFunction, args);
    }

    private BrowserListener<Object> sessionClosePromise() {
        BrowserListener<Object> disConnectLis = new BrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                throw new InstrumentException("Target closed");
            }
        };
        disConnectLis.setMethod(Variables.Event.CDPSESSION_DISCONNECTED.getName());
        this.client.addListener(disConnectLis.getMethod(), disConnectLis, true);
        return disConnectLis;
    }


    /**
     * 返回页面的地址
     *
     * @return 页面地址
     */
    private String url() {
        return this.getMainFrame().url();
    }

    private String screenshotTask(String format, ScreenshotOption options) throws IOException, ExecutionException, InterruptedException {
        Map<String, Object> params = new HashMap<>();
        params.put("targetId", this.target.getTargetId());
        this.client.send("Target.activateTarget", params, true);
        ClipOverwrite clip = null;
        if (options.getClip() != null) {
            clip = processClip(options.getClip());
        }
        if (options.getFullPage()) {
            JSONObject metrics = this.client.send("Page.getLayoutMetrics", null, true);
            double width = Math.ceil(metrics.getJSONObject("contentSize").getDouble("width"));
            double height = Math.ceil(metrics.getJSONObject("contentSize").getDouble("height"));
            clip = new ClipOverwrite(0, 0, width, height, 1);
            ScreenOrientation screenOrientation;
            if (this.viewport.getIsLandscape()) {
                screenOrientation = new ScreenOrientation(90, "landscapePrimary");
            } else {
                screenOrientation = new ScreenOrientation(0, "portraitPrimary");
            }
            params.clear();
            params.put("mobile", this.viewport.getIsMobile());
            params.put("width", width);
            params.put("height", height);
            params.put("deviceScaleFactor", this.viewport.getDeviceScaleFactor());
            params.put("screenOrientation", screenOrientation);
            this.client.send("Emulation.setDeviceMetricsOverride", params, true);
        }
        boolean shouldSetDefaultBackground = options.getOmitBackground() && "png".equals(format);
        if (shouldSetDefaultBackground) {
            setTransparentBackgroundColor();
        }
        params.clear();
        params.put("format", format);
        params.put("quality", options.getQuality());
        params.put("clip", clip);
        JSONObject result = this.client.send("Page.captureScreenshot", params, true);
        if (shouldSetDefaultBackground) {
            this.client.send("Emulation.setDefaultBackgroundColorOverride", null, true);
        }
        if (options.getFullPage() && this.viewport != null)
            this.setViewport(this.viewport);
        String data = result.getString("data");
//            byte[] buffer = decoder.decodeBuffer(data);
        byte[] buffer = Base64.getDecoder().decode(data);
        if (StringKit.isNotEmpty(options.getPath())) {
            Files.write(Paths.get(options.getPath()), buffer, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
        return data;
    }

    private void setTransparentBackgroundColor() {
        Map<String, Object> params = new HashMap<>();
        Map<String, Integer> colorMap = new HashMap<>();
        colorMap.put("r", 0);
        colorMap.put("g", 0);
        colorMap.put("b", 0);
        colorMap.put("a", 0);
        params.put("color", colorMap);
        this.client.send("Emulation.setDefaultBackgroundColorOverride", params, true);
    }

    private ClipOverwrite processClip(Clip clip) {
        long x = Math.round(clip.getX());
        long y = Math.round(clip.getY());
        long width = Math.round(clip.getWidth() + clip.getX() - x);
        long height = Math.round(clip.getHeight() + clip.getY() - y);
        return new ClipOverwrite(x, y, width, height, 1);
    }

    private void onFileChooser(FileChooserPayload event) {
        Builder.commonExecutor().submit(() -> {
            if (CollKit.isEmpty(this.fileChooserInterceptors))
                return;
            Frame frame = this.frameManager.frame(event.getFrameId());
            ExecutionContext context = frame.executionContext();
            ElementHandle element = context.adoptBackendNodeId(event.getBackendNodeId());
            Set<FileChooserCallBack> interceptors = new HashSet<>(this.fileChooserInterceptors);
            this.fileChooserInterceptors.clear();
            FileChooser fileChooser = new FileChooser(this.client, element, event);
            for (FileChooserCallBack interceptor : interceptors)
                interceptor.setFileChooser(fileChooser);
        });
    }

    private void onLogEntryAdded(EntryAddedPayload event) {
        if (CollKit.isNotEmpty(event.getEntry().getArgs()))
            event.getEntry().getArgs().forEach(arg -> Builder.releaseObject(this.client, arg, false));
        if (!"worker".equals(event.getEntry().getSource()))
            this.emit(Variables.Event.PAGE_CONSOLE.getName(), new ConsoleMessage(event.getEntry().getLevel(), event.getEntry().getText(), Collections.emptyList(), new Location(event.getEntry().getUrl(), event.getEntry().getLineNumber())));
    }

    private void emitMetrics(MetricPayload event) throws IllegalAccessException, IntrospectionException, InvocationTargetException {
        PageMetric pageMetric = new PageMetric();
        Metrics metrics = this.buildMetricsObject(event.getMetrics());
        pageMetric.setMetrics(metrics);
        pageMetric.setTitle(event.getTitle());
        this.emit(Variables.Event.PAGE_METRICS.getName(), pageMetric);
    }

    private void onTargetCrashed() {
        this.emit("error", new InstrumentException("Page crashed!"));
    }

    /**
     * 当js对话框出现的时候触发，比如alert, prompt, confirm 或者 beforeunload。Puppeteer可以通过Dialog's accept 或者 dismiss来响应弹窗。
     *
     * @param event 触发事件
     */
    private void onDialog(JavascriptDialogPayload event) {
        Variables.DialogType dialogType = null;
        if ("alert".equals(event.getType()))
            dialogType = Variables.DialogType.Alert;
        else if ("confirm".equals(event.getType()))
            dialogType = Variables.DialogType.Confirm;
        else if ("prompt".equals(event.getType()))
            dialogType = Variables.DialogType.Prompt;
        else if ("beforeunload".equals(event.getType()))
            dialogType = Variables.DialogType.BeforeUnload;
        Assert.isTrue(dialogType != null, "Unknown javascript dialog type: " + event.getType());
        Dialog dialog = new Dialog(this.client, dialogType, event.getMessage(), event.getDefaultPrompt());
        this.emit(Variables.Event.PAGE_DIALOG.getName(), dialog);
    }

    private void onConsoleAPI(ConsoleCalledPayload event) {
        if (event.getExecutionContextId() == 0) {
            // DevTools protocol stores the last 1000 console messages. These
            // messages are always reported even for removed execution contexts. In
            // this case, they are marked with executionContextId = 0 and are
            // reported upon enabling Runtime agent.
            //
            // Ignore these messages since:
            // - there's no execution context we can use to operate with message
            //   arguments
            // - these messages are reported before Puppeteer clients can subscribe
            //   to the 'console'
            //   page event.
            //
            // @see https://github.com/puppeteer/puppeteer/issues/3865
            return;
        }
        ExecutionContext context = this.frameManager.executionContextById(event.getExecutionContextId());
        List<JSHandle> values = new ArrayList<>();
        if (CollKit.isNotEmpty(event.getArgs())) {
            for (int i = 0; i < event.getArgs().size(); i++) {
                RemoteObject arg = event.getArgs().get(i);
                values.add(JSHandle.createJSHandle(context, arg));
            }
        }
        this.addConsoleMessage(event.getType(), values, event.getStackTrace());
    }

    private void onBindingCalled(BindingCalledPayload event) {
        String payloadStr = event.getPayload();
        Payload payload;
        payload = JSON.parseObject(payloadStr, Payload.class);

        String expression;
        try {
            Object result = this.pageBindings.get(event.getName()).apply(payload.getArgs());
            expression = Builder.evaluationString(deliverResult(), Variables.PageEvaluateType.FUNCTION, payload.getName(), payload.getSeq(), result);
        } catch (Exception e) {
            expression = Builder.evaluationString(deliverError(), Variables.PageEvaluateType.FUNCTION, payload.getName(), payload.getSeq(), e, e.getMessage());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("expression", expression);
        params.put("contextId", event.getExecutionContextId());
        this.client.send("Runtime.evaluate", params, false);
    }

    private String deliverError() {
        return "function deliverError(name, seq, message, stack) {\n" +
                "      const error = new Error(message);\n" +
                "      error.stack = stack;\n" +
                "      window[name]['callbacks'].get(seq).reject(error);\n" +
                "      window[name]['callbacks'].delete(seq);\n" +
                "    }";
    }

    private String deliverResult() {
        return "function deliverResult(name, seq, result) {\n" +
                "      window[name]['callbacks'].get(seq).resolve(result);\n" +
                "      window[name]['callbacks'].delete(seq);\n" +
                "    }";
    }

    private void addConsoleMessage(String type, List<JSHandle> args, StackTrace stackTrace) {
        if (this.getListenerCount(Variables.Event.PAGE_CONSOLE.getName()) == 0) {
            args.forEach(arg -> arg.dispose(false));
            return;
        }
        List<String> textTokens = new ArrayList<>();
        for (JSHandle arg : args) {
            RemoteObject remoteObject = arg.getRemoteObject();
            if (StringKit.isNotEmpty(remoteObject.getObjectId()))
                textTokens.add(arg.toString());
            else {
                textTokens.add(JSON.toJSONString(Builder.valueFromRemoteObject(remoteObject)));
            }
        }
        Location location = stackTrace != null && stackTrace.getCallFrames().size() > 0 ? new Location(stackTrace.getCallFrames().get(0).getUrl(), stackTrace.getCallFrames().get(0).getLineNumber(), stackTrace.getCallFrames().get(0).getColumnNumber()) : new Location();
        ConsoleMessage message = new ConsoleMessage(type, String.join(" ", textTokens), args, location);
        this.emit(Variables.Event.PAGE_CONSOLE.getName(), message);
    }

    private void handleException(ExceptionDetails exceptionDetails) {
        String message = Builder.getExceptionMessage(exceptionDetails);
        RuntimeException err = new RuntimeException(message);
        this.emit(Variables.Event.PAGE_PageError.getName(), err);
    }

    private Metrics buildMetricsObject(List<Metric> metrics) throws IntrospectionException, InvocationTargetException, IllegalAccessException {
        Metrics result = new Metrics();
        if (CollKit.isNotEmpty(metrics)) {
            for (Metric metric : metrics) {
                if (Variables.SUPPORTED_METRICS.contains(metric.getName())) {
                    PropertyDescriptor descriptor = new PropertyDescriptor(metric.getName(), Metrics.class);
                    descriptor.getWriteMethod().invoke(result, metric.getValue());
                }
            }
        }
        return result;
    }

    private Response go(int delta, NavigateOption options) {
        JSONObject historyNode = this.client.send("Page.getNavigationHistory", null, true);
        GetNavigationHistory history;
        history = JSON.toJavaObject(historyNode, GetNavigationHistory.class);

        NavigationEntry entry = history.getEntries().get(history.getCurrentIndex() + delta);
        if (entry == null)
            return null;
        Response response = this.waitForNavigation(options, null);
        Map<String, Object> params = new HashMap<>();
        params.put("entryId", entry.getId());
        this.client.send("Page.navigateToHistoryEntry", params, true);
        return response;
    }

    static class FileChooserCallBack {

        private CountDownLatch latch;
        private FileChooser fileChooser;

        public FileChooserCallBack() {
            super();
        }

        public FileChooserCallBack(CountDownLatch latch) {
            super();
            this.latch = latch;
        }

        public FileChooser getFileChooser() {
            return fileChooser;
        }

        public void setFileChooser(FileChooser fileChooser) {
            this.fileChooser = fileChooser;
            if (this.latch != null) {
                this.latch.countDown();
            }
        }

        public CountDownLatch getLatch() {
            return latch;
        }

        public void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        public void waitForFileChooser(int finalTimeout) throws InterruptedException {
            if (this.latch != null) {
                boolean await = this.latch.await(finalTimeout, TimeUnit.MILLISECONDS);
                if (!await) {
                    throw new InstrumentException("waiting for file chooser failed: timeout " + finalTimeout + "ms exceeded");
                }
            }
        }
    }

}
