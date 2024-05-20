
import javafx.util.Pair;
import java.util.ArrayList;
import java.util.*;

public abstract class MyUtils{

    protected static String getArgs(ArrayList<Pair<String, String>> args, boolean isDefinition){
        String rv = " (i8*" + (isDefinition ? " %this" : ""), type;
        int i = 0;
        if(args != null){
            for(Pair<String, String> arg : args){
                type = ClassData.getSize(arg.getKey()).getValue();
                rv += (i++ < args.size() ? ", " : "") + type + (isDefinition ? " %." + arg.getValue() : "");
            }
        }
        return rv + ")" + (isDefinition ? "" : "*");
    }

    protected static String declareMethods(String className, Map<String, MethodData> methods){
        String rv = "", methodName, retType;
        int i = 0;

        for(Map.Entry<String, MethodData> entry : methods.entrySet()){
            retType = ClassData.getSize(entry.getValue().returnType).getValue();
            methodName = entry.getKey();
            rv += "i8* bitcast (" + retType + MyUtils.getArgs(entry.getValue().arguments, false) + " @" + entry.getValue().className + "." + methodName + " to i8*)";
            rv += ++i < methods.size() ? ", " : "";
        }
        return rv;
    }

    protected static void declareVTable(LLVMGenerator obj){
        String className;
        Map<String, MethodData> methods;

        obj.emit(";for each class, declare a global vTable containing a pointer for each method");
        for(Map.Entry<String, ClassData> entry : obj.data.entrySet()){
            className = entry.getKey();
            methods = entry.getValue().methods;
            obj.emit("@." + className + "_vtable = global [" + methods.size() + " x i8*] [" + MyUtils.declareMethods(className, methods) + "]");
        }
    }

    protected static ArrayList<Pair<String, String>> getParams(String[] params){
        ArrayList<Pair<String, String>> rv = new ArrayList<Pair<String, String>>();
        int splitAt;
        for(String par : params){
            splitAt = par.indexOf(':');
            if(splitAt != -1)
                rv.add(new Pair<>(par.substring(0, splitAt), par.substring(splitAt+1)));
        }
        return rv;
    }

    public static String filterSignature(String signature, String classPointer){
        String[] args = signature.split(",");
        String rv = "";
        int len = args.length;

        if(len == 1)
            return "(i8*)" ;
        for(int i = 0; i < len; i++)
            rv += args[i].trim().split(" ")[0] + (i + 1 ==  len ? ")" : ", ");
        return rv;
    }
}