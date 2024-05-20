import javafx.util.Pair;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        boolean displayOffsets = Arrays.asList(args).contains("--offsets");

        for (String arg : args) {
            if (arg.equals("--offsets"))
                continue;

            try (FileInputStream fin = new FileInputStream(arg)) {
                CharStream input = CharStreams.fromStream(fin);
                JavaLexer lexer = new JavaLexer(input);
                CommonTokenStream tokens = new CommonTokenStream(lexer);
                JavaParser parser = new JavaParser(tokens);

                ParseTree tree = parser.goal();
                FirstVisitor v0 = new FirstVisitor();
                v0.visit(tree);

                if (displayOffsets) {
                    System.out.println("Offsets\n-------");

                    for (Map.Entry<String, ClassData> entry : v0.classes.entrySet()) {
                        String name = entry.getKey();
                        System.out.println("Class: " + name);

                        System.out.println("\n\tFields\n\t------\n\t\tthis: 0");
                        for (Map.Entry<String, Pair<String, Integer>> var : entry.getValue().vars.entrySet())
                            System.out.println("\t\t" + name + "." + var.getKey() + ": " + var.getValue().getValue());

                        System.out.println("\n\tMethods\n\t-------");
                        for (Map.Entry<String, MethodData> func : entry.getValue().methods.entrySet())
                            System.out.println("\t\t" + func.getValue().className + "." + func.getKey() + ": " + func.getValue().offset);
                    }
                }

                try (BufferedWriter fout = new BufferedWriter(new FileWriter(arg.replace(".java", ".ll")))) {
                    LLVMGenerator v1 = new LLVMGenerator(fout, v0.classes, v0.messageQueue);
                    v1.visit(tree);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (!displayOffsets)
            System.out.println("To view field and method offsets for each class rerun with --offsets");
    }
}
