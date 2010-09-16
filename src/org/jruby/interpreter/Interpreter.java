package org.jruby.interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;
import org.jruby.Ruby;
import org.jruby.RubyModule;
import org.jruby.ast.Node;
import org.jruby.compiler.ir.IRBuilder;
import org.jruby.compiler.ir.IRMethod;
import org.jruby.compiler.ir.IRScope;
import org.jruby.compiler.ir.IRScript;
import org.jruby.compiler.ir.compiler_pass.AddFrameInstructions;
import org.jruby.compiler.ir.compiler_pass.CFG_Builder;
import org.jruby.compiler.ir.compiler_pass.DominatorTreeBuilder;
import org.jruby.compiler.ir.compiler_pass.IR_Printer;
import org.jruby.compiler.ir.compiler_pass.LiveVariableAnalysis;
import org.jruby.compiler.ir.compiler_pass.opts.DeadCodeElimination;
import org.jruby.compiler.ir.compiler_pass.opts.LocalOptimizationPass;
import org.jruby.compiler.ir.instructions.BranchInstr;
import org.jruby.compiler.ir.instructions.CallInstr;
import org.jruby.compiler.ir.instructions.DefineClassMethodInstr;
import org.jruby.compiler.ir.instructions.DefineInstanceMethodInstr;
import org.jruby.compiler.ir.instructions.Instr;
import org.jruby.compiler.ir.instructions.JumpInstr;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.representations.BasicBlock;
import org.jruby.compiler.ir.representations.CFG;
import org.jruby.internal.runtime.methods.InterpretedIRMethod;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;


public class Interpreter {
    private Ruby runtime;

    public Interpreter(Ruby runtime) {
        this.runtime = runtime;
    }

    public static void main(String[] args) {
        Ruby runtime = Ruby.getGlobalRuntime();
        boolean isDebug = args.length > 0 && args[0].equals("-debug");
        int i = isDebug ? 1 : 0;
        boolean isCommandLineScript = args.length > i && args[i].equals("-e");
        i += (isCommandLineScript ? 1 : 0);
        while (i < args.length) {
            long t1 = new Date().getTime();
            Node ast = buildAST(runtime, isCommandLineScript, args[i]);
            long t2 = new Date().getTime();
            IRScope scope = new IRBuilder().buildRoot(ast);
            long t3 = new Date().getTime();
            if (isDebug) {
                System.out.println("## Before local optimization pass");
                scope.runCompilerPass(new IR_Printer());
            }
            scope.runCompilerPass(new LocalOptimizationPass());
            long t4 = new Date().getTime();
            if (isDebug) {
                System.out.println("## After local optimization");
                scope.runCompilerPass(new IR_Printer());
            }
            scope.runCompilerPass(new CFG_Builder());
            long t5 = new Date().getTime();
            scope.runCompilerPass(new DominatorTreeBuilder());
            long t6 = new Date().getTime();
            if (isDebug) System.out.println("## After dead code elimination");
            scope.runCompilerPass(new LiveVariableAnalysis());
            long t7 = new Date().getTime();
            scope.runCompilerPass(new DeadCodeElimination());
            long t8 = new Date().getTime();
            scope.runCompilerPass(new AddFrameInstructions());
            long t9 = new Date().getTime();
            if (isDebug) scope.runCompilerPass(new IR_Printer());
            new Interpreter(runtime).interpretTop(scope);
            long t10 = new Date().getTime();

            System.out.println("Time to build AST         : " + (t2 - t1));
            System.out.println("Time to build IR          : " + (t3 - t2));
            System.out.println("Time to run local opts    : " + (t4 - t3));
            System.out.println("Time to run build cfg     : " + (t5 - t4));
            System.out.println("Time to run build domtree : " + (t6 - t5));
            System.out.println("Time to run lva           : " + (t7 - t6));
            System.out.println("Time to run dead code elim: " + (t8 - t7));
            System.out.println("Time to add frame instrs  : " + (t9 - t8));
            i++;
        }
    }
        
    public static Node buildAST(Ruby runtime, boolean isCommandLineScript, String arg) {
        // inline script
        if (isCommandLineScript) return runtime.parse(ByteList.create(arg), "-e", null, 0, false);

        // from file
        try {
            System.out.println("-- processing " + arg + " --");
            return runtime.parseFile(new FileInputStream(new File(arg)), arg, null, 0);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    /*
    public void interpretTop(IR_Scope scope) {
        IRubyObject self = runtime.getTopSelf();

        if (scope instanceof IR_Script) {
            interpretMethod(self, ((IR_Script) scope).getRootClass().getRootMethod());
        } else {
            System.out.println("BONED");
        }
    }*/

    public void interpretTop(IRScope scope) {
        IRubyObject self = runtime.getTopSelf();

        if (!(scope instanceof IRScript)) {
            System.out.println("BONED (not IR_Script)");
            return;
        }

        IRMethod rootMethod = ((IRScript) scope).getRootClass().getRootMethod();
        RubyModule metaclass = self.getMetaClass();

        InterpretedIRMethod method = new InterpretedIRMethod(rootMethod, metaclass);

        method.call(runtime.getCurrentContext(), self, metaclass, "", new IRubyObject[]{});
    }

    public void interpretMethod(IRubyObject self, IRMethod method) {
        if (method == null) {
            System.out.println("Interpreting null method?");
            return;
        }
        System.out.print(method + "(");

        Operand operands[] = method.getCallArgs();
        for (int i = 0; i < operands.length; i++) {
            System.out.print(operands[i] + ", ");
        }
        System.out.println("EOP)");

        // Dummy start and end are canonical entry and exit points for a method/closures
        // we always getFallThroughBB(previous) to walk through unless we encounter explicit jump

        // IR_Scope 
        //   getLexicalScope <--- previous StaticScope equivalent
        
        // ThreadContext, self, receiver{, arg{, arg{, arg{, arg}}}}

        // Construct primitive array as simple store for temporary variables in method and pass along

        CFG cfg = method.getCFG();
/*        try {
            System.out.println("GETS:" + System.in.read());
        } catch (IOException ex) {
            Logger.getLogger(Interpreter.class.getName()).log(Level.SEVERE, null, ex);
        }*/



        for (BasicBlock basicBlock : cfg.getNodes()) {
            System.out.println("NEW BB");
            for (Instr i : basicBlock.getInstrs()) {
                // .. interpret i ..
                if (i instanceof BranchInstr) {
                    System.out.println("In branch");
                    BranchInstr branch = (BranchInstr) i;
                    boolean taken = false; // .. the interpreter will tell you whether the branch was taken or not ...
                    if (taken) {
                        basicBlock = cfg.getTargetBB(branch.getJumpTarget());
                    } else {
                        basicBlock = cfg.getFallThroughBB(basicBlock);
                    }
                } else if (i instanceof JumpInstr) {
                    System.out.println("In jump");
                    JumpInstr jump = (JumpInstr) i;
                    basicBlock = cfg.getTargetBB(jump.getJumpTarget());
                } else if (i instanceof CallInstr) {
                    CallInstr callInstruction = (CallInstr) i;

                    System.out.println("Call: " + callInstruction);

                    // Does not need to be recursive...except for scope handling
                    interpretMethod(self, callInstruction.getTargetMethod());
                } else if (i instanceof DefineClassMethodInstr) {
                    if (((DefineClassMethodInstr) i).method.getCFG() != cfg) {
                        System.out.println("def class method");
//                        createClassMethod(self, (DefineClassMethodInstr) i);
                    }
                } else if (i instanceof DefineInstanceMethodInstr) {
                    System.out.println("def instance method");
//                    createInstanceMethod(self, (DefineInstanceMethodInstr) i);
                } else {
                    System.out.println("NOT HANDLING: " + i + ", (" + i.getClass() + ")");
                }
                //... handle returns ..
            }

            if (basicBlock == null) {
                //.. you are done with this cfg /method ..
                //.. pop call stack, etc ..
            }
        }
    }

    public static IRubyObject interpret(ThreadContext context, CFG cfg, InterpreterContext interp) {

        try {
            BasicBlock basicBlock = cfg.getEntryBB();
            BasicBlock jumpBlock = basicBlock;

            while (basicBlock != null) {
                for (Instr instruction : basicBlock.getInstrs()) {
                    try {
                        System.out.println("EXEC'ing: " + instruction);
                        instruction.interpret(interp, (IRubyObject) interp.getSelf());
                    } catch (Jump jump) {
                        jumpBlock = cfg.getTargetBB(jump.getTarget());
                        break;
                    }
                }

                if (jumpBlock != basicBlock) { // Explicit jump needed from last instruction
                    basicBlock = jumpBlock;
                } else {                       // Implicit jump because we fell off current block
                    basicBlock = cfg.getFallThroughBB(basicBlock);
                    jumpBlock = basicBlock;
                }
            }

            return (IRubyObject) interp.getReturnValue();
        } finally {
            if (interp.getFrame() != null) {
                context.popFrame();
                interp.setFrame(null);
            }
            context.postMethodScopeOnly();
        }
    }
}
