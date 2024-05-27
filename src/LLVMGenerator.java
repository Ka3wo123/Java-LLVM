import javafx.util.Pair;

import java.io.*;
import java.util.*;

public class LLVMGenerator extends JavaParserBaseVisitor<String> {
    private BufferedWriter out;
    protected Map<String, ClassData> data;
    private LinkedList<String> messageQueue;
    private String className;
    private State state;
    private boolean inIfStatement;

    // Constructor: set a pointer to output file and set class data collected during the first pass
    LLVMGenerator(BufferedWriter out, Map<String, ClassData> data, LinkedList<String> messageQueue) {
        this.out = out;
        this.data = data;
        this.state = new State();
        this.messageQueue = messageQueue;
    }

    private String getArrayIndex(String index) {

        if (index.startsWith("%")) {
            emit("\n\t" + this.state.newReg() + " = add i32 " + index + ", 1");
            return "%_" + (this.state.getRegCounter() - 1);
        }

        return String.valueOf(Integer.parseInt(index) + 1);
    }

    private String getField(String field, boolean wantContent) {
        Pair<String, Integer> fieldInfo = this.data.get(this.className).vars.get(field);
        String reg = this.state.newReg(), llvmType = ClassData.getSize(fieldInfo.getKey()).getValue();

        emit("\n\t;load " + (wantContent ? "field " : "address of ") + this.className + "." + field + " from memory"
                + "\n\t" + reg + " = getelementptr i8, i8* %this, i32 " + fieldInfo.getValue());

        if (!"i8".equals(llvmType))
            emit("\t" + this.state.newReg() + " = bitcast i8* " + reg + " to " + llvmType + "*");

        if (wantContent)
            emit("\t" + this.state.newReg() + " = load " + llvmType + ", " + llvmType + "* " + ("%_" + (this.state.getRegCounter() - 2)));
        return llvmType + (wantContent ? " " : "* ") + "%_" + (this.state.getRegCounter() - 1);
    }

    private String getIdAddress(String id) {
        State.IdInfo info = this.state.getIdInfo(id);

        return (info == null) ? this.getField(id, false) : info.getType() + "* " + info.getRegister();
    }

    protected void emit(String s) {
        try {
            this.out.write(s + "\n");
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
        }
    }

    @Override
    public String visitGoal(JavaParser.GoalContext ctx) {
        MyUtils.declareVTable(this);

        emit("\n"
                + ";declare functions to be used\n"
                + "declare i8* @calloc(i32, i32)\n"
                + "declare i32 @printf(i8*, ...)\n"
                + "declare void @exit(i32)\n\n"

                + ";define constants and functions to be used\n"
                + "@_cint = constant [4 x i8] c\"%d\\0a\\00\"\n"
                + "@_cOOB = constant [15 x i8] c\"Out of bounds\\0a\\00\"\n"
                + "define void @print_int(i32 %i) {\n"
                + "\t%_str = bitcast [4 x i8]* @_cint to i8*\n"
                + "\tcall i32 (i8*, ...) @printf(i8* %_str, i32 %i)\n"
                + "\tret void\n}\n\n"

                + "define void @throw_oob() {\n"
                + "\t%_str = bitcast [15 x i8]* @_cOOB to i8*\n"
                + "\tcall i32 (i8*, ...) @printf(i8* %_str)\n"
                + "\tcall void @exit(i32 1)\n"
                + "\tret void\n}\n\n"
                + "@_ctrue = constant [6 x i8] c\"true\\0a\\00\"\n"
                + "@_cfalse = constant [7 x i8] c\"false\\0a\\00\"\n"

                + "define void @print_bool(i1 %i){\n"
                + "\tbr i1 %i, label %is_true, label %is_false\n\n"
                + "is_true:\n"
                + "\t%_res_true = bitcast [6 x i8]* @_ctrue to i8*\n"
                + "\tbr label %result\n\n"
                + "is_false:\n"
                + "\t%_res_false = bitcast [7 x i8]* @_cfalse to i8*\n"
                + "\tbr label %result\n\n"
                + "result:\n"
                + "\t%_res = phi i8* [%_res_true, %is_true], [%_res_false, %is_false]\n"
                + "\tcall i32 (i8*, ...) @printf(i8* %_res)\n"
                + "\tret void\n}\n");

        ctx.mainClass().accept(this);
        return null;
    }

    @Override
    public String visitMainClass(JavaParser.MainClassContext ctx) {
        this.className = ctx.identifier(0).accept(this);
        emit("define i32 @main() {");

        if (ctx.varDeclaration(0) != null) {
            ctx.varDeclaration(0).accept(this);
        }
        if (ctx.statement(0) != null) {
            ctx.statement(0).accept(this);
        }

        emit("\tret i32 0\n}\n");

        return null;
    }

    @Override
    public String visitTypeDeclaration(JavaParser.TypeDeclarationContext ctx) {
        ctx.classDeclaration().accept(this);
        return null;
    }

    @Override
    public String visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
        this.className = ctx.identifier().accept(this);

        for (int i = 0; i < ctx.methodDeclaration().size(); i++) {
            ctx.methodDeclaration(i).accept(this);
        }

//        ctx.methodDeclaration(0).accept(this);

        return null;
    }

    @Override
    public String visitClassExtendsDeclaration(JavaParser.ClassExtendsDeclarationContext ctx) {
        this.className = ctx.identifier(0).accept(this);

        for (int i = 0; i < ctx.methodDeclaration().size(); i++) {
            ctx.methodDeclaration(i).accept(this);
        }
//        ctx.methodDeclaration(0).accept(this);

        return null;
    }

    @Override
    public String visitVarDeclaration(JavaParser.VarDeclarationContext ctx) {
        String varType = ClassData.getSize(ctx.type().accept(this)).getValue();
        String id = ctx.identifier().accept(this);

        emit("\n\t;allocate space for local variable %" + id + "\n\t%" + id + " = alloca " + varType);
        this.state.put(id, "%" + id, varType); // keep track of the register holding that address
        return null;
    }

    @Override
    public String visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
        String id = ctx.identifier().accept(this);
        String returnType = ClassData.getSize(ctx.type().accept(this)).getValue();

        ArrayList<Pair<String, String>> parameters = this.data.get(this.className).methods.get(id).arguments;
        emit(";" + this.className + "." + id + "\ndefine " + returnType + " @" + this.className + "." + id + MyUtils.getArgs(parameters, true) + "{");

        String llvmType, paramID;
        if (parameters != null) {
            emit("\t;allocate space and store each parameter of the method");
            for (Pair<String, String> par : parameters) {
                paramID = par.getValue();
                llvmType = ClassData.getSize(par.getKey()).getValue();
                emit("\t%" + paramID + " = alloca " + llvmType +
                        "\n\tstore " + llvmType + " %." + paramID + ", " + llvmType + "* %" + paramID);
                this.state.put(paramID, "%" + paramID, llvmType);
            }
        }

        for(int i = 0; i < ctx.expression().children.size(); i++) {
            ctx.varDeclaration(i).accept(this);
        }

        for(int i = 0; i < ctx.statement().size(); i++) {
            ctx.statement(i).accept(this);
        }
//        ctx.varDeclaration(0).accept(this);
//        ctx.statement(0).accept(this);

        emit("\tret " + ctx.expression().accept(this) + "\n}\n");
        this.state.clear();
        return null;
    }

    @Override
    public String visitAssignmentStatement(JavaParser.AssignmentStatementContext ctx) {
        String leftID = ctx.identifier().accept(this);
        String rightSide = ctx.expression().accept(this);
        String leftType, leftReg, rightType = rightSide.split(" ")[0];
        String[] leftInfo = this.getIdAddress(leftID).split(" ");
        leftType = leftInfo[0];
        leftReg = leftInfo[1];

        if (!leftType.equals(rightType + "*")) {
            emit("\n\t;adjust pointer type of left operand\n\t"
                    + this.state.newReg() + " = bitcast " + leftType + " " + leftReg + " to " + rightType + "*");
            leftType = rightType + "*";
            leftReg = "%_" + (this.state.getRegCounter() - 1);
        }

        emit("\n\t;store result\n\tstore " + rightSide + ", " + leftType + " " + leftReg);
        return null;
    }

    @Override
    public String visitArrayAssignmentStatetment(JavaParser.ArrayAssignmentStatetmentContext ctx) {
        String leftID = ctx.identifier().accept(this), leftInfo = this.getIdAddress(leftID);

        String index = ctx.expression(0).accept(this).split(" ")[1];
        String rightSide = ctx.expression(0).accept(this);

        this.checkArrayIndex(leftInfo.split(" ")[1], index, false);
        index = this.getArrayIndex(index);

        emit("\n\t;assign a value to the array element\n\t" + this.state.newReg() + " = load i8*, " + leftInfo + "\n\t"
                + this.state.newReg() + " = bitcast i8* %_" + (this.state.getRegCounter() - 2) + " to i32*\n\t"
                + this.state.newReg() + " = getelementptr i32, i32* %_" + (this.state.getRegCounter() - 2) + " , i32 " + index
                + "\n\tstore " + rightSide + ", i32* %_" + (this.state.getRegCounter() - 1));
        return null;
    }

    @Override
    public String visitIfStatement(JavaParser.IfStatementContext ctx) {
        String[] ifLabel = this.state.newLabel("if");
        String condition = ctx.expression().accept(this), brEnd = "\tbr label %" + ifLabel[2] + "\n\n";

        this.inIfStatement = true;
        emit("\n\t;if statement\n\tbr " + condition + " ,label %" + ifLabel[0] + ", label %" + ifLabel[1] + "\n\n" + ifLabel[0] + ":");

        for(int i = 0; i < ctx.statement().size(); i++) {
            ctx.statement(i).accept(this);
        }
//        ctx.statement(0).accept(this);
        emit(brEnd + ifLabel[1] + ":");

        for(int i = 0; i < ctx.statement().size(); i++) {
            ctx.statement(i).accept(this);
        }

//        ctx.statement(0).accept(this);
        emit(brEnd + ifLabel[2] + ":");
        this.inIfStatement = false;

        return null;
    }

    @Override
    public String visitWhileStatement(JavaParser.WhileStatementContext ctx) {
        String[] whileLabel = this.state.newLabel("while");
        String condition;

        emit("\n\t;while statement\n\tbr label %" + whileLabel[0] + "\n\n" + whileLabel[0] + ":");
        condition = ctx.expression().accept(this);
        emit("\tbr " + condition + " ,label %" + whileLabel[1] + ", label %" + whileLabel[2] + "\n\n" + whileLabel[1] + ":");
        ctx.statement().accept(this);
        emit("\n\tbr label %" + whileLabel[0] + "\n" + whileLabel[2] + ":\n");

        return condition;
    }

    @Override
    public String visitPrintStatement(JavaParser.PrintStatementContext ctx) {
        String expr = ctx.expression().accept(this);
        String type = expr.split(" ")[0];

        emit("\n\t;display an " + type + "\n\tcall void (" + type + ") @print_" + (type.equals("i1") ? "bool" : "int") + "(" + expr + ")");
        return null;
    }

//    @Override
//    public String visitExpression(JavaParser.ExpressionContext ctx) {
//        int index =  ctx.getRuleIndex();
//
//
//    }


    @Override
    public String visitMessageSend(JavaParser.MessageSendContext ctx) {
        String classPointer = ctx.primaryExpression().accept(this);
        String methodName = ctx.identifier().accept(this), signature, returnType;
        MethodData methodData = this.data.get(this.messageQueue.removeFirst()).methods.get(methodName);

        int offset = methodData.offset;
        returnType = ClassData.getSize(methodData.returnType).getValue();
        signature = ctx.expressionList().isEmpty() ? ctx.expressionList().accept(this).replaceFirst("[(]", "(" + classPointer + ", ") : "(" + classPointer + ")";


        emit("\t" + this.state.newReg() + " = bitcast " + classPointer + " to i8*** \t\t\t\t;%_" + (this.state.getRegCounter() - 1) + " points to the vTable"
                + "\n\t" + this.state.newReg() + " = load i8**, i8*** %_" + (this.state.getRegCounter() - 2) + "\t\t\t\t;%_" + (this.state.getRegCounter() - 1)
                + " is the vTable\n\t" + this.state.newReg() + " = getelementptr i8*, i8** %_" + (this.state.getRegCounter() - 2) + ", i32 " + offset + "\t;%_"
                + (this.state.getRegCounter() - 1) + " points to the address of " + methodName
                + "\n\t" + this.state.newReg() + " = load i8*, i8** %_" + (this.state.getRegCounter() - 2)
                + "\t\t\t\t\t;%_" + (this.state.getRegCounter() - 1) + " points to the body of " + methodName
                + "\n\t" + this.state.newReg() + " = bitcast i8* %_" + (this.state.getRegCounter() - 2) + " to " + returnType + " "
                + MyUtils.filterSignature(signature, classPointer) + "*\t;%_cast pointer to the appropriate size\n\t"
                + this.state.newReg() + " = call " + returnType + " %_" + (this.state.getRegCounter() - 2) + signature);
        return returnType + " %_" + (this.state.getRegCounter() - 1);
    }

    @Override
    public String visitExpressionList(JavaParser.ExpressionListContext ctx) {
        return "(" + ctx.expression().accept(this) + ctx.expressionTail().accept(this) + ")";
    }

    @Override
    public String visitExpressionTail(JavaParser.ExpressionTailContext ctx) {
        String rv = "";

        for (int i = 0; i < ctx.expressionTerm().size(); i++)
            rv += ctx.expressionTerm().get(i).accept(this);
        return rv;
    }

    @Override
    public String visitExpressionTerm(JavaParser.ExpressionTermContext ctx) {
        return ", " + ctx.expression().accept(this);
    }

    @Override
    public String visitArrayLookup(JavaParser.ArrayLookupContext ctx) {
        String id = ctx.primaryExpression(0).accept(this);
        String index = ctx.primaryExpression(0).accept(this).split(" ")[1];

        this.checkArrayIndex(id, index, true);

        return this.getArrayElement(id, this.getArrayIndex(index));
    }

    @Override
    public String visitCompareExpression(JavaParser.CompareExpressionContext ctx) {
        return arithmeticExpression(ctx.primaryExpression(0).accept(this), ctx.primaryExpression(0).accept(this), "icmp slt");
    }

    @Override
    public String visitPlusExpression(JavaParser.PlusExpressionContext ctx) {
        return arithmeticExpression(ctx.primaryExpression(0).accept(this), ctx.primaryExpression(0).accept(this), "add");
    }

    @Override
    public String visitMinusExpression(JavaParser.MinusExpressionContext ctx) {
        return arithmeticExpression(ctx.primaryExpression(0).accept(this), ctx.primaryExpression(0).accept(this), "sub");
    }

    @Override
    public String visitTimesExpression(JavaParser.TimesExpressionContext ctx) {
        return arithmeticExpression(ctx.primaryExpression(0).accept(this), ctx.primaryExpression(0).accept(this), "mul");
    }

    @Override
    public String visitAndExpression(JavaParser.AndExpressionContext ctx) {
        String leftReg = ctx.clause(0).accept(this), rightReg;
        String[] labels = this.state.newLabel("and");

        emit("\t;short-circuit and clause, right side gets evaluated if and only if left side evaluates to true\n"
                + "\tbr " + leftReg + ", label %" + labels[0] + ", label %" + labels[1] + "\n\n" + labels[0] + ":\n\t");

        rightReg = ctx.clause(0).accept(this);

        emit("\tbr label %" + labels[2] + "\n\n" + labels[1] + ":\n\n\tbr label %" + labels[2] + "\n\n" + labels[2] + ":\n\n"
                + "\t" + this.state.newReg() + " = phi i1 [" + rightReg.split(" ")[1] + ", %" + labels[0] + "],"
                + "[" + leftReg.split(" ")[1] + ", %" + labels[1] + "]");
        return "i1 %_" + (this.state.getRegCounter() - 1);
    }

    @Override
    public String visitClause(JavaParser.ClauseContext ctx) {
        return ctx.primaryExpression().accept(this);
    }

    @Override
    public String visitPrimaryExpression(JavaParser.PrimaryExpressionContext ctx) {
        String child = visit(ctx.getChild(0));
        State.IdInfo id;

        if (ctx.identifier() != null) {
            id = this.state.getIdInfo(child);

            if (id == null) {
                return this.getField(child, true);
            }

            emit("\n\t;loading local variable '" + child + "' from stack\n\t"
                    + this.state.newReg() + " = load " + id.getType() + ", " + id.getType() + "* " + id.getRegister());
            return id.getType() + " %_" + (this.state.getRegCounter() - 1);
        }

        return child;
    }

    @Override
    public String visitType(JavaParser.TypeContext ctx) {
        if (ctx.arrayType() != null) {
            return "array";
        } else if (ctx.booleanType() != null) {
            return "boolean";
        } else if (ctx.integerType() != null) {
            return "integer";
        } else if (ctx.identifier() != null) {
            return visit(ctx.identifier());
        } else {
            throw new IllegalArgumentException("Unknown type");
        }
    }

    @Override
    public String visitBooleanType(JavaParser.BooleanTypeContext ctx) {
        return "boolean";
    }

    @Override
    public String visitArrayType(JavaParser.ArrayTypeContext ctx) {
        return "array";
    }

    @Override
    public String visitIntegerType(JavaParser.IntegerTypeContext ctx) {
        return "integer";
    }

    @Override
    public String visitIntegerLiteral(JavaParser.IntegerLiteralContext ctx) {
        return "i32 " + ctx.DECIMAL_LITERAL();
    }

    @Override
    public String visitBoolLiteral(JavaParser.BoolLiteralContext ctx) {
        return "i1 " + ctx.BOOL_LITERAL();
    }

    @Override
    public String visitThisExpression(JavaParser.ThisExpressionContext ctx) {
        return "i8* %this";
    }

    @Override
    public String visitArrayAllocationExpression(JavaParser.ArrayAllocationExpressionContext ctx) {
        String size = ctx.expression().accept(this).split(" ")[1];
        emit("\n\t;allocate space for new array of size " + size + " + 1 place to store size at\n\t"
                + this.state.newReg() + " = add i32 " + size + ", 1\n"
                + "\t" + this.state.newReg() + " = call i8* @calloc(i32 4, i32 %_" + (this.state.getRegCounter() - 2) + ")\n"
                + "\t" + this.state.newReg() + " = bitcast i8* %_" + (this.state.getRegCounter() - 2) + " to i32*\n"
                + "\n\t;store size at index 0\n\tstore i32 " + size + ", i32* %_" + (this.state.getRegCounter() - 1));
        return "i32* %_" + (this.state.getRegCounter() - 1);
    }

    @Override
    public String visitAllocationExpression(JavaParser.AllocationExpressionContext ctx) {
        String className = ctx.identifier().accept(this), tableSize;
        ClassData data = this.data.get(className);
        tableSize = "[" + data.methods.size() + " x i8*]";

        emit("\n\t;allocate space for a new \"" + className + "\" object\n\t" + this.state.newReg() + " = call i8* @calloc(i32 1, i32 " + data.size + ")"
                + "\n\t" + this.state.newReg() + " = bitcast i8* %_" + (this.state.getRegCounter() - 2) + " to i8***\n"
                + "\t" + this.state.newReg() + " = getelementptr " + tableSize + ", " + tableSize + "* @." + className + "_vtable, i32 0, i32 0\n"
                + "\tstore i8** %_" + (this.state.getRegCounter() - 1) + ", i8*** %_" + (this.state.getRegCounter() - 2));
        return "i8* %_" + (this.state.getRegCounter() - 3);
    }

    @Override
    public String visitNotExpression(JavaParser.NotExpressionContext ctx) {
        String clause = ctx.clause().accept(this);
        emit("\n\t;apply logical not, using xor\n\t" + this.state.newReg() + " = xor " + clause + ", 1");
        return "i1 %_" + (this.state.getRegCounter() - 1);
    }

    @Override
    public String visitBracketExpression(JavaParser.BracketExpressionContext ctx) {
        return ctx.expression().accept(this);
    }

    public String arithmeticExpression(String left, String right, String op) {
        String[] rightInfo = right.split(" ");
        emit("\n\t;apply arithmetic expression\n"
                + "\t" + this.state.newReg() + " = " + op + " " + left + ", " + rightInfo[1]);
        return (op.equals("icmp slt") ? "i1" : "i32") + " %_" + (this.state.getRegCounter() - 1);
    }

    public String getArrayElement(String id, String index) {
        String comment = index.equals("0") ? ";get length of array at " + id.split(" ")[1] : ";lookup *(" + id.split(" ")[1] + " + " + index + ")";

        emit("\n\t" + comment + "\n"
                + "\t" + this.state.newReg() + " = bitcast " + id + " to i32*\n"
                + "\t" + this.state.newReg() + " = getelementptr i32, i32* %_" + (this.state.getRegCounter() - 2) + ", i32 " + index
                + "\n\t" + this.state.newReg() + " = load i32, i32* %_" + (this.state.getRegCounter() - 2));
        return "i32 %_" + (this.state.getRegCounter() - 1);
    }


    public void checkArrayIndex(String id, String index, boolean loaded) {
        String len;
        String[] label = this.state.newLabel("oob");

        // get length of array
        if (!loaded) {
            emit("\n\t;load array\n\t" + this.state.newReg() + " = load i8*, i8** " + id);
            len = this.getArrayElement("i8* %_" + (this.state.getRegCounter() - 1), "0").split(" ")[1];
        } else
            len = this.getArrayElement(id, "0").split(" ")[1];

        // (index < 0) xor (index < array.length) ? then inBounds : else throw outOfBounds exception
        emit("\n\t;make sure index \"" + index + "\" is within bounds\n\t" + this.state.newReg() + " = icmp slt i32 " + index + ", 0"
                + "\n\t" + this.state.newReg() + " = icmp slt i32 " + index + ", " + len
                + "\n\t" + this.state.newReg() + " = xor i1 %_" + (this.state.getRegCounter() - 3) + ", %_" + (this.state.getRegCounter() - 2)
                + "\n\tbr i1 %_" + (this.state.getRegCounter() - 1) + ", label %" + label[1] + ", label %" + label[0] + "\n\n" + label[0]
                + ":\n\n\tcall void @throw_oob()\n\tbr label %" + label[1] + "\n\n" + label[1] + ":");
    }


}
