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
package org.aoju.lancia.option;

import org.aoju.lancia.Variables;
import org.aoju.lancia.kernel.Standard;
import org.aoju.lancia.kernel.page.Viewport;

import java.util.List;

/**
 * 构建启动器
 *
 * @author Kimi Liu
 * @version 1.2.8
 * @since JDK 1.8+
 */
public class LaunchBuilder {

    private final LaunchOption options;

    public LaunchBuilder() {
        options = new LaunchOption();
    }

    public LaunchBuilder executablePath(String executablePath) {
        options.setExecutablePath(executablePath);
        return this;
    }

    /**
     * 是否忽略所欲的默认启动参数，默认是fasle
     *
     * @param ignoreAllDefaultArgs true为忽略所有启动参数
     * @return LaunchOptionsBuilder
     */
    public LaunchBuilder ignoreDefaultArgs(boolean ignoreAllDefaultArgs) {
        options.setIgnoreAllDefaultArgs(ignoreAllDefaultArgs);
        return this;
    }

    /**
     * 忽略指定的默认启动参数，默认的启动参数见 {@link Variables#DEFAULT_ARGS}
     *
     * @param ignoreDefaultArgs 要忽略的启动参数
     * @return LaunchOptionsBuilder
     */
    public LaunchBuilder ignoreDefaultArgs(List<String> ignoreDefaultArgs) {
        options.setIgnoreDefaultArgs(ignoreDefaultArgs);
        return this;
    }

    public LaunchBuilder handleSIGINT(boolean handleSIGINT) {
        options.setHandleSIGINT(handleSIGINT);
        return this;
    }

    public LaunchBuilder handleSIGTERM(boolean handleSIGTERM) {
        options.setHandleSIGTERM(handleSIGTERM);
        return this;
    }

    public LaunchBuilder handleSIGHUP(boolean handleSIGHUP) {
        options.setHandleSIGHUP(handleSIGHUP);
        return this;
    }

    public LaunchBuilder env(Standard env) {
        options.setEnv(env);
        return this;
    }

    public LaunchBuilder pipe(boolean pipe) {
        options.setPipe(pipe);
        return this;
    }

    public LaunchBuilder withProduct(String product) {
        options.setProduct(product);
        return this;
    }

    public LaunchBuilder ignoreHTTPSErrors(boolean ignoreHTTPSErrors) {
        options.setIgnoreHTTPSErrors(ignoreHTTPSErrors);
        return this;
    }

    public LaunchBuilder viewport(Viewport viewport) {
        options.setViewport(viewport);
        return this;
    }

    public LaunchBuilder slowMo(int slowMo) {
        options.setSlowMo(slowMo);
        return this;
    }

    public LaunchBuilder headless(boolean headless) {
        options.setHeadless(headless);
        return this;
    }

    public LaunchBuilder args(List<String> args) {
        options.setArgs(args);
        return this;
    }

    public LaunchBuilder userDataDir(String userDataDir) {
        options.setUserDataDir(userDataDir);
        return this;
    }

    public LaunchBuilder devtools(boolean devtools) {
        options.setDevtools(devtools);
        return this;
    }

    public LaunchOption build() {
        return options;
    }

}
