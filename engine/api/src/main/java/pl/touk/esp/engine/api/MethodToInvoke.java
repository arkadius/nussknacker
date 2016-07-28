package pl.touk.esp.engine.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MethodToInvoke {

    //TODO: hmmm... a jak tu w sumie dac null???
    Class<?> returnType() default Object.class;

}