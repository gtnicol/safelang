package io.safelang.compiler.bytecode;

import io.safelang.ast.CaseExpressionNode;
import io.safelang.ast.EnumPatternNode;
import io.safelang.ast.LiteralNode;
import io.safelang.bytecode.*;
import java.util.ArrayList;
import java.util.HashSet;

final class BytecodeCaseCompiler {

  private final BytecodeCaseContext context;

  BytecodeCaseCompiler(final BytecodeCaseContext context) {
    this.context = context;
  }

  void compile(final CaseExpressionNode node) {
    context.compile(node.subject());

    final var exits = new ArrayList<Integer>();

    for (final var branch : node.branches()) {
      if (branch.isWildcard()) {
        if (branch.hasGuard()) {
          context.compile(branch.guard());
          final var guard = context.chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);
          context.chunk().emitOpcode(OpCode.POP);
          context.compile(branch.result());
          exits.add(context.chunk().emitJumpPlaceholder(OpCode.JUMP));
          context.chunk().patch(guard, (context.chunk().position() - (guard + 2)) & 0xFFFF);
        } else {
          context.chunk().emitOpcode(OpCode.POP);
          context.compile(branch.result());
          exits.add(context.chunk().emitJumpPlaceholder(OpCode.JUMP));
        }
        continue;
      }

      final var pattern = branch.pattern();

      if (pattern instanceof LiteralNode) {
        context.chunk().emitOpcode(OpCode.DUP);
        context.compile(pattern);
        context.chunk().emitOpcode(OpCode.CMP_EQ);
        final var skip = context.chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);

        if (branch.hasGuard()) {
          context.compile(branch.guard());
          final var guard = context.chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);
          context.chunk().emitOpcode(OpCode.POP);
          context.compile(branch.result());
          exits.add(context.chunk().emitJumpPlaceholder(OpCode.JUMP));
          context.chunk().patch(guard, (context.chunk().position() - (guard + 2)) & 0xFFFF);
        } else {
          context.chunk().emitOpcode(OpCode.POP);
          context.compile(branch.result());
          exits.add(context.chunk().emitJumpPlaceholder(OpCode.JUMP));
        }

        final var miss = context.chunk().position();
        context.chunk().patch(skip, (miss - (skip + 2)) & 0xFFFF);
      } else if (pattern instanceof EnumPatternNode enumeration) {
        context.chunk().emitOpcode(OpCode.DUP);

        final var info = context.module().variant(enumeration.variant());
        if (info != null) {
          final var tag = info.getVariantIndex(enumeration.variant());
          context.chunk().emitOpcode(OpCode.MATCH_ENUM);
          context.chunk().emitShort(tag);
          final var patch = context.chunk().position();
          context.chunk().emitShort(0);

          final var snapshot = new HashSet<>(context.slots().keySet());
          if (enumeration.hasBindings()) {
            for (int index = 0; index < enumeration.bindings().size(); index++) {
              final var name = enumeration.bindings().get(index);
              context.chunk().emitOpcode(OpCode.ENUM_DATA);
              context.chunk().emitByte(index);
              final var slot = context.allocate(name);
              context.chunk().emitOpShort(OpCode.STORE_LOCAL, slot);
            }
          }

          if (branch.hasGuard()) {
            context.compile(branch.guard());
            final var guard = context.chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);
            context.chunk().emitOpcode(OpCode.POP);
            context.compile(branch.result());
            exits.add(context.chunk().emitJumpPlaceholder(OpCode.JUMP));
            context.chunk().patch(guard, (context.chunk().position() - (guard + 2)) & 0xFFFF);
          } else {
            context.chunk().emitOpcode(OpCode.POP);
            context.compile(branch.result());
            exits.add(context.chunk().emitJumpPlaceholder(OpCode.JUMP));
          }

          context.slots().keySet().retainAll(snapshot);
          final var miss = context.chunk().position();
          context.chunk().patch(patch, (miss - (patch + 2)) & 0xFFFF);
        }
      }
    }

    if (node.hasFallback()) {
      context.chunk().emitOpcode(OpCode.POP);
      context.compile(node.fallback());
    } else {
      context.chunk().emitOpcode(OpCode.POP);
      context.chunk().emitOpcode(OpCode.PUSH_VOID);
    }

    final var end = context.chunk().position();
    for (final var exit : exits) {
      context.chunk().patch(exit, (end - (exit + 2)) & 0xFFFF);
    }
  }
}
