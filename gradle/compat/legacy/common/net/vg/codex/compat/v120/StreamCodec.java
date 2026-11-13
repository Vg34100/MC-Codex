//? if <1.21 {
/*package net.vg.codex.compat.v120;
import java.util.function.*;
public interface StreamCodec<B,V> {
 V decode(B buffer); void encode(B buffer,V value);
 static <B,V> StreamCodec<B,V> of(BiConsumer<B,V> writer,Function<B,V> reader){return new StreamCodec<>(){public V decode(B b){return reader.apply(b);}public void encode(B b,V v){writer.accept(b,v);}};}
 static <B,V> StreamCodec<B,V> unit(V value){return of((b,v)->{},b->value);}
 default <R> StreamCodec<B,R> map(Function<? super V,? extends R> reader,Function<? super R,? extends V> writer){var self=this;return of((b,v)->self.encode(b,writer.apply(v)),b->reader.apply(self.decode(b)));}
 default <R> R apply(Function<StreamCodec<B,V>,R> operation){return operation.apply(this);}
 static <BUF,T,A> StreamCodec<BUF,T> composite(StreamCodec<? super BUF,A> c0,Function<T,A> f0,Function<A,T> create){return of((buffer,value)->{c0.encode(buffer,f0.apply(value));},buffer->create.apply(c0.decode(buffer)));}
 static <BUF,T,A,B> StreamCodec<BUF,T> composite(StreamCodec<? super BUF,A> c0,Function<T,A> f0,StreamCodec<? super BUF,B> c1,Function<T,B> f1,BiFunction<A,B,T> create){return of((buffer,value)->{c0.encode(buffer,f0.apply(value));c1.encode(buffer,f1.apply(value));},buffer->create.apply(c0.decode(buffer),c1.decode(buffer)));}
 static <BUF,T,A,B,C> StreamCodec<BUF,T> composite(StreamCodec<? super BUF,A> c0,Function<T,A> f0,StreamCodec<? super BUF,B> c1,Function<T,B> f1,StreamCodec<? super BUF,C> c2,Function<T,C> f2,Fn3<A,B,C,T> create){return of((buffer,value)->{c0.encode(buffer,f0.apply(value));c1.encode(buffer,f1.apply(value));c2.encode(buffer,f2.apply(value));},buffer->create.apply(c0.decode(buffer),c1.decode(buffer),c2.decode(buffer)));}
 @FunctionalInterface interface Fn3<A,B,C,T>{T apply(A v0,B v1,C v2);}
 static <BUF,T,A,B,C,D> StreamCodec<BUF,T> composite(StreamCodec<? super BUF,A> c0,Function<T,A> f0,StreamCodec<? super BUF,B> c1,Function<T,B> f1,StreamCodec<? super BUF,C> c2,Function<T,C> f2,StreamCodec<? super BUF,D> c3,Function<T,D> f3,Fn4<A,B,C,D,T> create){return of((buffer,value)->{c0.encode(buffer,f0.apply(value));c1.encode(buffer,f1.apply(value));c2.encode(buffer,f2.apply(value));c3.encode(buffer,f3.apply(value));},buffer->create.apply(c0.decode(buffer),c1.decode(buffer),c2.decode(buffer),c3.decode(buffer)));}
 @FunctionalInterface interface Fn4<A,B,C,D,T>{T apply(A v0,B v1,C v2,D v3);}
 static <BUF,T,A,B,C,D,E> StreamCodec<BUF,T> composite(StreamCodec<? super BUF,A> c0,Function<T,A> f0,StreamCodec<? super BUF,B> c1,Function<T,B> f1,StreamCodec<? super BUF,C> c2,Function<T,C> f2,StreamCodec<? super BUF,D> c3,Function<T,D> f3,StreamCodec<? super BUF,E> c4,Function<T,E> f4,Fn5<A,B,C,D,E,T> create){return of((buffer,value)->{c0.encode(buffer,f0.apply(value));c1.encode(buffer,f1.apply(value));c2.encode(buffer,f2.apply(value));c3.encode(buffer,f3.apply(value));c4.encode(buffer,f4.apply(value));},buffer->create.apply(c0.decode(buffer),c1.decode(buffer),c2.decode(buffer),c3.decode(buffer),c4.decode(buffer)));}
 @FunctionalInterface interface Fn5<A,B,C,D,E,T>{T apply(A v0,B v1,C v2,D v3,E v4);}
 static <BUF,T,A,B,C,D,E,F> StreamCodec<BUF,T> composite(StreamCodec<? super BUF,A> c0,Function<T,A> f0,StreamCodec<? super BUF,B> c1,Function<T,B> f1,StreamCodec<? super BUF,C> c2,Function<T,C> f2,StreamCodec<? super BUF,D> c3,Function<T,D> f3,StreamCodec<? super BUF,E> c4,Function<T,E> f4,StreamCodec<? super BUF,F> c5,Function<T,F> f5,Fn6<A,B,C,D,E,F,T> create){return of((buffer,value)->{c0.encode(buffer,f0.apply(value));c1.encode(buffer,f1.apply(value));c2.encode(buffer,f2.apply(value));c3.encode(buffer,f3.apply(value));c4.encode(buffer,f4.apply(value));c5.encode(buffer,f5.apply(value));},buffer->create.apply(c0.decode(buffer),c1.decode(buffer),c2.decode(buffer),c3.decode(buffer),c4.decode(buffer),c5.decode(buffer)));}
 @FunctionalInterface interface Fn6<A,B,C,D,E,F,T>{T apply(A v0,B v1,C v2,D v3,E v4,F v5);}
}
*///?}
