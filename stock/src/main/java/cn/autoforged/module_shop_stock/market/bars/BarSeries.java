package cn.autoforged.module_shop_stock.market.bars;

import cn.autoforged.module_shop_stock.market.Bar;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;

/**
 * 定长环形缓冲的 K 线序列：满了追加会丢最老的一根，随机访问 O(1)。物理数组按需增长，不会一次占满上限。
 * 以 {@link List} 形式暴露，旧代码的 {@code bars.get/size/add/clear} 直接可用（不支持中间插入/删除）。
 */
public final class BarSeries extends AbstractList<Bar> implements RandomAccess {
    private Bar[] buf = new Bar[16];
    private int head;
    private int size;
    private int limit;

    public BarSeries(int limit) {
        this.limit = Math.max(2, limit);
    }

    public int limit() {
        return this.limit;
    }

    /** 调小会立刻丢掉最老的柱；调大只是放宽。 */
    public void setLimit(int limit) {
        this.limit = Math.max(2, limit);
        while (this.size > this.limit) {
            dropHead();
        }
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public Bar get(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + this.size);
        }
        return this.buf[(this.head + index) % this.buf.length];
    }

    public Bar last() {
        return this.size == 0 ? null : get(this.size - 1);
    }

    @Override
    public boolean add(Bar bar) {
        if (this.size >= this.limit) {
            dropHead();
        }
        if (this.size == this.buf.length) {
            grow();
        }
        this.buf[(this.head + this.size) % this.buf.length] = bar;
        this.size++;
        this.modCount++;
        return true;
    }

    @Override
    public void clear() {
        Arrays.fill(this.buf, null);
        this.head = 0;
        this.size = 0;
        this.modCount++;
    }

    /** 把更早的历史接到最前面（超出上限时丢最老的）。 */
    public void prepend(Collection<Bar> older) {
        if (older.isEmpty()) {
            return;
        }
        List<Bar> keep = new ArrayList<>(this);
        clear();
        int total = older.size() + keep.size();
        int skip = Math.max(0, total - this.limit);
        int index = 0;
        for (Bar bar : older) {
            if (index++ >= skip) {
                add(bar);
            }
        }
        for (Bar bar : keep) {
            if (index++ >= skip) {
                add(bar);
            }
        }
    }

    private void dropHead() {
        this.buf[this.head] = null;
        this.head = (this.head + 1) % this.buf.length;
        this.size--;
        this.modCount++;
    }

    private void grow() {
        int next = Math.max(16, Math.min(Math.max(this.limit, this.size + 1), this.buf.length * 2));
        Bar[] copy = new Bar[next];
        for (int i = 0; i < this.size; i++) {
            copy[i] = this.buf[(this.head + i) % this.buf.length];
        }
        this.buf = copy;
        this.head = 0;
    }
}
