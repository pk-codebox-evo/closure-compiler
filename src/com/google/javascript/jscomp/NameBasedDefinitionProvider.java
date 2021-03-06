/*
 * Copyright 2016 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.javascript.jscomp.DefinitionsRemover.Definition;
import com.google.javascript.jscomp.DefinitionsRemover.ExternalNameOnlyDefinition;
import com.google.javascript.jscomp.DefinitionsRemover.UnknownDefinition;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple name-based definition gatherer that implements {@link DefinitionProvider}.
 *
 * <p>It treats all variable writes as happening in the global scope and treats all objects as
 * capable of having the same set of properties. The current implementation only handles definitions
 * whose right hand side is an immutable value or function expression. All complex definitions are
 * treated as unknowns.
 *
 * <p>This definition simply uses the variable name to determine a new definition site so
 * potentially it could return multiple definition sites for a single variable.  Although we could
 * use the type system to make this more accurate, in practice after disambiguate properties has
 * run, names are unique enough that this works well enough to accept the performance gain.
 */
public class NameBasedDefinitionProvider implements DefinitionProvider, CompilerPass {
  protected final Multimap<String, Definition> nameDefinitionMultimap = LinkedHashMultimap.create();
  protected final Map<Node, DefinitionSite> definitionSiteMap = new LinkedHashMap<>();
  protected final AbstractCompiler compiler;

  protected boolean hasProcessBeenRun = false;

  public NameBasedDefinitionProvider(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node source) {
    if (hasProcessBeenRun) {
      return;
    }
    this.hasProcessBeenRun = true;

    NodeTraversal.traverseEs6(compiler, externs, new DefinitionGatheringCallback(true));
    NodeTraversal.traverseEs6(compiler, source, new DefinitionGatheringCallback(false));
  }

  @Override
  public Collection<Definition> getDefinitionsReferencedAt(Node useSite) {
    if (definitionSiteMap.containsKey(useSite)) {
      return null;
    }

    Preconditions.checkState(hasProcessBeenRun, "The process was not run");
    if (useSite.isGetProp()) {
      String propName = useSite.getLastChild().getString();
      if (propName.equals("apply") || propName.equals("call")) {
        useSite = useSite.getFirstChild();
      }
    }

    String name = getSimplifiedName(useSite);
    if (name != null) {
      Collection<Definition> defs = nameDefinitionMultimap.get(name);
      if (!defs.isEmpty()) {
        return defs;
      } else {
        return null;
      }
    } else {
      return null;
    }
  }

  private class DefinitionGatheringCallback implements Callback {
    private final boolean inExterns;

    DefinitionGatheringCallback(boolean inExterns) {
      this.inExterns = inExterns;
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      if (inExterns) {
        if (n.isFunction() && !n.getFirstChild().isName()) {
          // No need to crawl functions in JSDoc
          return false;
        }
        if (parent != null && parent.isFunction() && n != parent.getFirstChild()) {
          // Arguments of external functions should not count as name
          // definitions.  They are placeholder names for documentation
          // purposes only which are not reachable from anywhere.
          return false;
        }
      }
      return true;
    }

    @Override
    public void visit(NodeTraversal traversal, Node node, Node parent) {
      if (inExterns && node.getJSDocInfo() != null) {
        for (Node typeRoot : node.getJSDocInfo().getTypeNodes()) {
          traversal.traverse(typeRoot);
        }
      }

      Definition def = DefinitionsRemover.getDefinition(node, inExterns);
      if (def != null) {
        String name = getSimplifiedName(def.getLValue());
        if (name != null) {
          Node rValue = def.getRValue();
          if ((rValue != null) && !NodeUtil.isImmutableValue(rValue) && !rValue.isFunction()) {

            // Unhandled complex expression
            Definition unknownDef = new UnknownDefinition(def.getLValue(), inExterns);
            def = unknownDef;
          }

          // TODO(johnlenz) : remove this stub dropping code if it becomes
          // illegal to have untyped stubs in the externs definitions.
          if (inExterns) {
            // We need special handling of untyped externs stubs here:
            // the stub should be dropped if the name is provided elsewhere.

            // If there is no qualified name for this, then there will be
            // no stubs to remove. This will happen if node is an object
            // literal key.
            if (node.isQualifiedName()) {
              for (Definition prevDef : new ArrayList<>(nameDefinitionMultimap.get(name))) {
                if (prevDef instanceof ExternalNameOnlyDefinition
                    && !jsdocContainsDeclarations(node)) {
                  if (node.matchesQualifiedName(prevDef.getLValue())) {
                    // Drop this stub, there is a real definition.
                    nameDefinitionMultimap.remove(name, prevDef);
                  }
                }
              }
            }
          }

          nameDefinitionMultimap.put(name, def);
          definitionSiteMap.put(
              node,
              new DefinitionSite(
                  node, def, traversal.getModule(), traversal.inGlobalScope(), inExterns));
        }
      }

      if (inExterns && (parent != null) && parent.isExprResult()) {
        String name = getSimplifiedName(node);
        if (name != null) {

          // TODO(johnlenz) : remove this code if it becomes illegal to have
          // stubs in the externs definitions.

          // We need special handling of untyped externs stubs here:
          //    the stub should be dropped if the name is provided elsewhere.
          // We can't just drop the stub now as it needs to be used as the
          //    externs definition if no other definition is provided.

          boolean dropStub = false;
          if (!jsdocContainsDeclarations(node) && node.isQualifiedName()) {
            for (Definition prevDef : nameDefinitionMultimap.get(name)) {
              if (node.matchesQualifiedName(prevDef.getLValue())) {
                dropStub = true;
                break;
              }
            }
          }

          if (!dropStub) {
            // Incomplete definition
            Definition definition = new ExternalNameOnlyDefinition(node);
            nameDefinitionMultimap.put(name, definition);
            definitionSiteMap.put(
                node,
                new DefinitionSite(
                    node, definition, traversal.getModule(), traversal.inGlobalScope(), inExterns));
          }
        }
      }
    }

    /** @return Whether the node has a JSDoc that actually declares something. */
    private boolean jsdocContainsDeclarations(Node node) {
      JSDocInfo info = node.getJSDocInfo();
      return (info != null && info.containsDeclaration());
    }
  }

  /**
   * Extract a name from a node. In the case of GETPROP nodes, replace the namespace or object
   * expression with "this" for simplicity and correctness at the expense of inefficiencies due to
   * higher chances of name collisions.
   *
   * <p>TODO(user) revisit. it would be helpful to at least use fully qualified names in the case of
   * namespaces. Might not matter as much if this pass runs after "collapsing properties".
   */
  protected static String getSimplifiedName(Node node) {
    if (node.isName()) {
      String name = node.getString();
      if (name != null && !name.isEmpty()) {
        return name;
      } else {
        return null;
      }
    } else if (node.isGetProp()) {
      return "this." + node.getLastChild().getString();
    }
    return null;
  }


  /**
   * Returns the collection of definition sites found during traversal.
   *
   * @return definition site collection.
   */
  public Collection<DefinitionSite> getDefinitionSites() {
    return definitionSiteMap.values();
  }

  public DefinitionSite getDefinitionForFunction(Node function) {
    Preconditions.checkState(hasProcessBeenRun, "The process was not run");
    Preconditions.checkState(function.isFunction());
    return definitionSiteMap.get(NodeUtil.getNameNode(function));
  }
}
