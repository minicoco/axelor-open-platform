/**
 * Copyright (c) 2012-2014 Axelor. All Rights Reserved.
 *
 * The contents of this file are subject to the Common Public
 * Attribution License Version 1.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://license.axelor.com/.
 *
 * The License is based on the Mozilla Public License Version 1.1 but
 * Sections 14 and 15 have been added to cover use of software over a
 * computer network and provide for limited attribution for the
 * Original Developer. In addition, Exhibit A has been modified to be
 * consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is part of "Axelor Business Suite", developed by
 * Axelor exclusively.
 *
 * The Original Developer is the Initial Developer. The Initial Developer of
 * the Original Code is Axelor.
 *
 * All portions of the code written by Axelor are
 * Copyright (c) 2012-2014 Axelor. All Rights Reserved.
 */
package com.axelor.meta.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Singleton;
import javax.persistence.PersistenceException;
import javax.xml.bind.JAXBException;

import org.reflections.vfs.Vfs;

import com.axelor.auth.db.Group;
import com.axelor.common.FileUtils;
import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.meta.MetaScanner;
import com.axelor.meta.db.MetaAction;
import com.axelor.meta.db.MetaActionMenu;
import com.axelor.meta.db.MetaChart;
import com.axelor.meta.db.MetaChartConfig;
import com.axelor.meta.db.MetaChartSeries;
import com.axelor.meta.db.MetaMenu;
import com.axelor.meta.db.MetaSelect;
import com.axelor.meta.db.MetaSelectItem;
import com.axelor.meta.db.MetaView;
import com.axelor.meta.schema.ObjectViews;
import com.axelor.meta.schema.actions.Action;
import com.axelor.meta.schema.views.AbstractView;
import com.axelor.meta.schema.views.AbstractWidget;
import com.axelor.meta.schema.views.ChartView;
import com.axelor.meta.schema.views.Field;
import com.axelor.meta.schema.views.FormView;
import com.axelor.meta.schema.views.GridView;
import com.axelor.meta.schema.views.MenuItem;
import com.axelor.meta.schema.views.Selection;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.inject.persist.Transactional;

@Singleton
public class ViewLoader extends AbstractLoader {

	@Override
	@Transactional
	public void load(Module module, boolean update) {
		try {
			for (Vfs.File file : MetaScanner.findAll(module.getName(), "views", "(.*?)\\.xml")) {
				log.info("importing: {}", file.getName());
				try {
					process(file.openInputStream(), module, update);
				} catch (IOException | JAXBException e) {
					throw Throwables.propagate(e);
				}
			}

			Set<?> unresolved = this.unresolvedKeys();
			if (unresolved.size() > 0) {
				log.error("unresolved items: {}", unresolved);
				throw new PersistenceException("There are some unresolve items, check the log.");
			}
			
			// generate default views
			importDefault(module);
		
		} finally {
			this.clear();
		}
	}

	private static <T> List<T> getList(List<T> list) {
		if (list == null) {
			return Lists.newArrayList();
		}
		return list;
	}

	private void process(InputStream stream, Module module, boolean update) throws JAXBException {
		final ObjectViews all = XMLViews.unmarshal(stream);
		
		for (AbstractView view : getList(all.getViews())) {
			importView(view, module, update);
		}
		
		for (Selection selection : getList(all.getSelections())) {
			importSelection(selection, module, update);
		}
		
		for (Action action : getList(all.getActions())) {
			importAction(action, module, update);
		}
		
		for (MenuItem item : getList(all.getMenus())) {
			importMenu(item, module, update);
		}
		
		for (MenuItem item: getList(all.getActionMenus())) {
			importActionMenu(item, module, update);
		}
	}
	
	private void importView(AbstractView view, Module module, boolean update) {

		String xmlId = view.getId();
		String name = view.getName();
		String type = view.getType();
		String modelName = view.getModel();

		if (xmlId != null && isVisited("view", xmlId)) {
			return;
		}
		
		log.info("Loading view: {}", name);

		String xml = XMLViews.toXml(view, true);

		if (type.matches("tree|chart|portal|search")) {
			modelName = null;
		} else if (StringUtils.isBlank(modelName)) {
			throw new IllegalArgumentException("Invalid view, model name missing.");
		}
		
		if (view instanceof ChartView) {
			importChart((ChartView) view, module, update);
			return;
		}
		
		if (modelName != null) {
			Class<?> model;
			try {
				model = Class.forName(modelName);
			} catch (ClassNotFoundException e) {
				throw new IllegalArgumentException("Invalid view, model not found: " + modelName);
			}
			modelName = model.getName();
		}

		MetaView entity = new MetaView(name);
		MetaView existing = xmlId == null ?
				MetaView.findByModule(name, module.getName()) :
				MetaView.findByID(xmlId);

		if (existing != null) {
			
			if (xmlId == null) {
				if (!update) {
					log.warn("duplicate view without 'id': {}", name);
				}
				return;
			}
			
			// set priority higher to existing view
			if (!Objects.equal(xmlId, existing.getXmlId())) {
				entity.setPriority(existing.getPriority() + 1);
			} else if (update) {
				entity = existing;
			}
		}
		
		if (isUpdated(entity)) {
			return;
		}

		entity.setXmlId(xmlId);
		entity.setTitle(view.getDefaultTitle());
		entity.setType(type);
		entity.setModel(modelName);
		entity.setModule(module.getName());
		entity.setXml(xml);
		
		entity = entity.save();
	}
	
	private void importChart(ChartView view, Module module, boolean update) {

		if (isVisited("chart", view.getName())) {
			return;
		}

		MetaChart entity = MetaChart.findByName(view.getName());
		if (entity == null) {
			entity = new MetaChart(view.getName());
		}
		
		if (isUpdated(entity)) {
			return;
		}
		
		entity.clearChartSeries();
		entity.clearChartConfig();
		
		entity.setModule(module.getName());
		entity.setTitle(view.getDefaultTitle());
		entity.setStacked(view.getStacked());
		
		String query = StringUtils.stripIndent(view.getQuery().getText());
		entity.setQuery(query);
		entity.setQueryType(view.getQuery().getType());

		entity.setCategoryKey(view.getCategory().getKey());
		entity.setCategoryType(view.getCategory().getType());
		entity.setCategoryTitle(view.getCategory().getDefaultTitle());
		
		for(ChartView.ChartSeries series : view.getSeries()) {
			MetaChartSeries item = new MetaChartSeries();
			item.setKey(series.getKey());
			item.setGroupBy(series.getGroupBy());
			item.setType(series.getType());
			item.setSide(series.getSide());
			item.setAggregate(series.getAggregate());
			entity.addChartSeries(item);
		}

		if (view.getConfig() != null) {
			for(ChartView.ChartConfig config : view.getConfig()) {
				MetaChartConfig item = new MetaChartConfig();
				item.setName(config.getName());
				item.setValue(config.getValue());
				entity.addChartConfig(item);
			}
		}
		
		entity.save();
	}
	
	private void importSelection(Selection selection, Module module, boolean update) {
		
		if (isVisited("select", selection.getName())) {
			return;
		}
		
		log.info("Loading selection : {}", selection.getName());
		
		MetaSelect select = MetaSelect.findByName(selection.getName());
		if (select == null) {
			select = new MetaSelect(selection.getName());
		}

		if (isUpdated(select)) {
			return;
		}
		
		select.clearItems();
		select.setModule(module.getName());
		
		int sequence = 0;
		for(Selection.Option opt : selection.getOptions()) {
			MetaSelectItem item = new MetaSelectItem();
			item.setValue(opt.getValue());
			item.setTitle(opt.getDefaultTitle());
			item.setOrder(sequence++);
			select.addItem(item);
		}
		
		select.save();
	}
	
	private Set<Group> findGroups(String groups) {
		if (StringUtils.isBlank(groups)) {
			return null;
		}
		
		Set<Group> all = Sets.newHashSet();
		for(String name : groups.split(",")) {
			Group group = Group.all().filter("self.code = ?1", name).fetchOne();
			if (group == null) {
				log.info("Creating a new user group: {}", name);
				group = new Group();
				group.setCode(name);
				group.setName(name);
				group = group.save();
			}
			all.add(group);
		}

		return all;
	}

	private void importAction(Action action, Module module, boolean update) {
		
		if (isVisited("action", action.getName())) {
			return;
		}
		
		log.info("Loading action : {}", action.getName());
		
		Class<?> klass = action.getClass();
		Mapper mapper = Mapper.of(klass);
	
		MetaAction entity = MetaAction.findByName(action.getName());
		if (entity == null) {
			entity = new MetaAction(action.getName());
		}

		if (isUpdated(entity)) {
			return;
		}
		
		entity.setXml(XMLViews.toXml(action,  true));
		
		String model = (String) mapper.get(action, "model");
		entity.setModel(model);
		entity.setModule(module.getName());

		String type = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, klass.getSimpleName());
		entity.setType(type);

		entity = entity.save();

		for (MetaMenu pending : this.resolve(MetaMenu.class, entity.getName())) {
			log.info("Resolved menu: {}", pending.getName());
			pending.setAction(entity);
			pending.save();
		}
	}
	
	private void importMenu(MenuItem menuItem, Module module, boolean update) {

		if (isVisited("menu", menuItem.getName())) {
			return;
		}

		log.info("Loading menu : {}", menuItem.getName());

		MetaMenu menu = MetaMenu.findByName(menuItem.getName());
		if (menu == null) {
			menu = new MetaMenu(menuItem.getName());
		}

		if (isUpdated(menu)) {
			return;
		}

		menu.setPriority(menuItem.getPriority());
		menu.setTitle(menuItem.getDefaultTitle());
		menu.setIcon(menuItem.getIcon());
		menu.setModule(module.getName());
		menu.setTop(menuItem.getTop());
		menu.setLeft(menuItem.getLeft() == null ? true : menuItem.getLeft());
		menu.setMobile(menuItem.getMobile());
		
		menu.clearGroups();
		menu.setGroups(this.findGroups(menuItem.getGroups()));

		if (!Strings.isNullOrEmpty(menuItem.getParent())) {
			MetaMenu parent = MetaMenu.findByName(menuItem.getParent());
			if (parent == null) {
				log.info("Unresolved parent : {}", menuItem.getParent());
				this.setUnresolved(MetaMenu.class, menuItem.getParent(), menu);
			} else {
				menu.setParent(parent);
			}
		}
		
		if (!StringUtils.isBlank(menuItem.getAction())) {
			MetaAction action = MetaAction.findByName(menuItem.getAction());
			if (action == null) {
				log.info("Unresolved action: {}", menuItem.getAction());
				setUnresolved(MetaMenu.class, menuItem.getAction(), menu);
			} else {
				menu.setAction(action);
			}
		}
		
		menu = menu.save();
		
		for (MetaMenu pending : this.resolve(MetaMenu.class, menu.getName())) {
			log.info("Resolved menu : {}", pending.getName());
			pending.setParent(menu);
			pending.save();
		}
	}
	
	private void importActionMenu(MenuItem menuItem, Module module, boolean update) {

		if (isVisited("menu", menuItem.getName())) {
			return;
		}
		
		log.info("Loading action menu : {}", menuItem.getName());

		MetaActionMenu menu = MetaActionMenu.findByName(menuItem.getName());
		if (menu == null) {
			menu = new MetaActionMenu(menuItem.getName());
		}
		
		if (isUpdated(menu)) {
			return;
		}

		menu.setTitle(menuItem.getDefaultTitle());
		menu.setModule(module.getName());
		menu.setCategory(menuItem.getCategory());
		
		if (!StringUtils.isBlank(menuItem.getParent())) {
			MetaActionMenu parent = MetaActionMenu.findByName(menuItem.getParent());
			if (parent == null) {
				log.info("Unresolved parent : {}", menuItem.getParent());
				this.setUnresolved(MetaActionMenu.class, menuItem.getParent(), menu);
			} else {
				menu.setParent(parent);
			}
		}

		if (!Strings.isNullOrEmpty(menuItem.getAction())) {
			MetaAction action = MetaAction.findByName(menuItem.getAction());
			if (action == null) {
				log.info("Unresolved action: {}", menuItem.getAction());
				this.setUnresolved(MetaActionMenu.class, menuItem.getAction(), menu);
			} else {
				menu.setAction(action);
			}
		}

		menu = menu.save();

		for (MetaActionMenu pending : this.resolve(MetaActionMenu.class, menu.getName())) {
			log.info("Resolved action menu : {}", pending.getName());
			pending.setParent(menu);
			pending.save();
		}
	}

	private static final File outputDir = FileUtils.getFile(System.getProperty("java.io.tmpdir"), "axelor", "generated");

	private void importDefault(Module module) {
		for (Class<?> klass: JPA.models()) {
			if (module.hasEntity(klass) && MetaView.all().filter("self.model = ?1", klass.getName()).count() == 0) {
				File out = FileUtils.getFile(outputDir, "views", klass.getSimpleName() + ".xml");
				String xml = createDefaultViews(module, klass);
				try {
					log.info("Creating default views: {}", out);
					Files.createParentDirs(out);
					Files.write(xml, out, Charsets.UTF_8);
				} catch (IOException e) {
					log.error("Unable to create: {}", out);
				}
			}
		}
	}
	
	@SuppressWarnings("all")
	private String createDefaultViews(Module module, final Class<?> klass) {

		final FormView formView = new FormView();
		final GridView gridView = new GridView();

		String name = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, klass.getSimpleName());
		String title = klass.getSimpleName();

		formView.setName(name + "-form");
		gridView.setName(name + "-grid");

		formView.setModel(klass.getName());
		gridView.setModel(klass.getName());

		formView.setTitle(title);
		gridView.setTitle(title);

		List<AbstractWidget> formItems = Lists.newArrayList();
		List<AbstractWidget> gridItems = Lists.newArrayList();

		Mapper mapper = Mapper.of(klass);
		List<String> fields = Lists.reverse(fieldNames(klass));

		for(String n : fields) {

			Property p = mapper.getProperty(n);

			if (p == null || p.isPrimary() || p.isVersion())
				continue;

			Field field = new Field();
			field.setName(p.getName());

			if (p.isCollection()) {
				field.setColSpan(4);
				field.setShowTitle(false);
			} else {
				gridItems.add(field);
			}
			formItems.add(field);
		}

		formView.setItems(formItems);
		gridView.setItems(gridItems);


		importView(formView, module, false);
		importView(gridView, module, false);

		return XMLViews.toXml(ImmutableList.of(gridView, formView), false);
	}
	
	// Fields names are not in ordered but some JVM implementation can.
	private List<String> fieldNames(Class<?> klass) {
		List<String> result = new ArrayList<String>();
		for(java.lang.reflect.Field field : klass.getDeclaredFields()) {
			if (!field.getName().matches("id|version|selected|created(By|On)|updated(By|On)")) {
				result.add(field.getName());
			}
		}
		if (klass.getSuperclass() != Object.class) {
			result.addAll(fieldNames(klass.getSuperclass()));
		}
		return Lists.reverse(result);
	}
}
