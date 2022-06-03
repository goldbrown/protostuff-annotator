package com.dush.psannotator;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.lang.jvm.annotation.JvmAnnotationAttribute;
import com.intellij.lang.jvm.annotation.JvmAnnotationAttributeValue;
import com.intellij.lang.jvm.annotation.JvmAnnotationConstantValue;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.CodeBlock;
import io.protostuff.Tag;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author dushmantha
 * Date 2018/1/16
 */
public class GenerateAction extends AnAction
{

	@Override public void actionPerformed( AnActionEvent e )
	{
		PsiClass psiClass = getPsiClassFromContext( e );

		if ( psiClass != null )
		{
			GenerateDialog dialog = new GenerateDialog( psiClass );
			dialog.show();
			if ( dialog.isOK() )
			{
				generateTags( psiClass, dialog.getFields(), dialog.getStartingValue(), dialog.isStaticFieldsEnabled(), dialog.isTransientFieldsEnabled() );
			}
		}

	}

	private void generateTags( PsiClass psiClass, List<PsiField> fields, int startTagNum, boolean staticFieldsEnabled, boolean transientFieldsEnabled )
	{
		if ( startTagNum < 0 || psiClass == null )
		{
			return;
		}
		AtomicReference<String> tip = new AtomicReference<>("");
		new WriteCommandAction.Simple( psiClass.getContainingFile().getProject(), psiClass.getContainingFile() )
		{
			@Override protected void run()
			{

				PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory( getProject() );
				Pair<Integer, List<Integer>> pair = getCurMaxNo(fields);
				checkTagConflict(tip, pair.getRight());
				if (StringUtils.isNotBlank(tip.get())) {
					return;
				}
				int index = Math.max(startTagNum, pair.getLeft() + 1);
				for ( PsiField field : fields )
				{
					PsiModifierList modifierList = field.getModifierList();
					if ( field.hasModifierProperty( PsiModifier.STATIC ) && !staticFieldsEnabled )
					{
						continue;
					}
					if ( field.hasModifierProperty( PsiModifier.TRANSIENT ) && !transientFieldsEnabled )
					{
						continue;
					}
					if ( field.getContainingClass() != null && !( field.getContainingClass().equals( psiClass ) ) )
					{
						index++;
						continue;
					}
					// already has @tag, skip it
					if (field.hasAnnotation("io.protostuff.Tag")) {
						continue;
					}
					AnnotationSpec tagAnnotation = AnnotationSpec.builder(Tag.class)
							.addMember("value", CodeBlock.builder().add(index++ + "").build())
							.build();
					PsiAnnotation annotationFromText = elementFactory.createAnnotationFromText(tagAnnotation.toString() + " ", psiClass );
					field.addBefore( annotationFromText, modifierList);
					tip.set("Insert tag success");
				}
				JavaCodeStyleManager.getInstance(getProject()).shortenClassReferences(psiClass);
				if (StringUtils.isEmpty(tip.get())) {
					tip.set("There is no tag to insert");
				}
			}
		}.execute();
		//  shift tip into here in case of error
		Messages.showInfoMessage(tip.get(), "Insert Tag");
	}

	private void checkTagConflict(AtomicReference<String> tip, List<Integer> tagVals) {
		if (CollectionUtils.isEmpty(tagVals)) {
			return;
		}
		Set<Integer> set = new HashSet<>(tagVals);
		List<Integer> dupList = tagVals.stream()                              // list 对应的 Stream
				.collect(Collectors.toMap(e -> e, e -> 1, Integer::sum)) // 获得元素出现频率的 Map，键为元素，值为元素出现的次数
				.entrySet()
				.stream()                              // 所有 entry 对应的 Stream
				.filter(e -> e.getValue() > 1)         // 过滤出元素出现次数大于 1 (重复元素）的 entry
				.map(Map.Entry::getKey)                // 获得 entry 的键（重复元素）对应的 Stream
				.collect(Collectors.toList());// 转化为 List
		if (set.size() != tagVals.size()) {
			tip.set("There are tag conflict, please check it before you insert tags. Conflict tag values are: " + dupList);
		}
	}

	private Pair<Integer, List<Integer>> getCurMaxNo(List<PsiField> fields) {
		int maxNo = 0;
		List<Integer> alreadyHas = Lists.newArrayList();
		for ( PsiField field : fields )
		{
			// already has @tag, count it
			PsiAnnotation tagAnnotation = field.getAnnotation("io.protostuff.Tag");
			if (tagAnnotation != null) {
				List<JvmAnnotationAttribute> attributes = tagAnnotation.getAttributes();
				for (JvmAnnotationAttribute attribute : attributes) {
					if ("value".equals(attribute.getAttributeName())) {
						JvmAnnotationAttributeValue attributeValue = attribute.getAttributeValue();
						if (attributeValue instanceof JvmAnnotationConstantValue) {
							JvmAnnotationConstantValue tmp = (JvmAnnotationConstantValue) attributeValue;
							int tmpVal = tmp.getConstantValue() != null ? (int) tmp.getConstantValue() : 0;
							maxNo = Math.max(maxNo, tmpVal);
							alreadyHas.add(tmpVal);
						}
					}
				}
			}
		}
		return Pair.of(maxNo, alreadyHas);
	}

	@Override public void update( AnActionEvent e )
	{
		PsiClass psiClass = getPsiClassFromContext( e );
		e.getPresentation().setEnabled( psiClass != null );
	}

	private PsiClass getPsiClassFromContext( AnActionEvent e )
	{
		PsiFile psiFile = e.getData( LangDataKeys.PSI_FILE );
		Editor editor = e.getData( PlatformDataKeys.EDITOR );

		if ( psiFile == null || editor == null )
		{
			return null;
		}
		int offset = editor.getCaretModel().getOffset();
		PsiElement elementAt = psiFile.findElementAt( offset );
		return PsiTreeUtil.getParentOfType( elementAt, PsiClass.class );
	}
}
