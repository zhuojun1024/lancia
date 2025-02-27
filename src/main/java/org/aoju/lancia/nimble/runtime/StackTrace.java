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
package org.aoju.lancia.nimble.runtime;

import java.util.List;

/**
 * 堆栈信息
 *
 * @author Kimi Liu
 * @version 1.2.8
 * @since JDK 1.8+
 */
public class StackTrace {

    /**
     * 此堆栈跟踪的字符串标签。对于异步跟踪，这可能是启动异步调用的函数的名称。
     */
    private String description;
    /**
     * 函数名称
     */
    private List<CallFrame> callFrames;
    /**
     * 如果存在，则在此堆栈之前的异步JavaScript堆栈跟踪。
     */
    private StackTrace parent;
    /**
     * 如果存在，则在此堆栈之前的异步JavaScript堆栈跟踪。
     */
    private StackTraceId parentId;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<CallFrame> getCallFrames() {
        return callFrames;
    }

    public void setCallFrames(List<CallFrame> callFrames) {
        this.callFrames = callFrames;
    }

    public StackTrace getParent() {
        return parent;
    }

    public void setParent(StackTrace parent) {
        this.parent = parent;
    }

    public StackTraceId getParentId() {
        return parentId;
    }

    public void setParentId(StackTraceId parentId) {
        this.parentId = parentId;
    }

}
