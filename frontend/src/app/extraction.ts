import {Component,computed,effect,inject,signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Api} from './api';
interface Column {name:string;dataType:string;numeric:boolean;deltaType:string;}
interface Delta {mode:string;column:string;lowerSource:string;upperSource:string;initialValue:string;flagValue:string;lowerOperator:string;upperOperator:string;}
interface TableOption {group:string;controlSumColumn:string;delta:Delta;}
interface Settings {tableIds:number[];engine:string;delimiter:string;parallelJobs:number;systemCode:string;countryCode:string;outputDirectory:string;deliveryDirectory:string;fileNamePattern:string;sqlclExecutable:string|null;tableOptions:Record<string,{delta:Delta|null}>;}
@Component({standalone:true,imports:[FormsModule],template:`
<div class="page-heading"><div class="eyebrow">EDAC EXTRACTION</div><h1>Extraction project</h1><p>Generate a Spring Boot source project for an external job scheduler.</p></div>
<section class="panel"><label>Source database connection<select [ngModel]="api.selectedId()" (ngModelChange)="changeConnection($event)">@for(c of api.connections();track c.id){<option [ngValue]="c.id">{{c.name}} — {{c.databaseType}}</option>}</select></label>
@if(api.selected()?.databaseType!=='ORACLE'){<p>Select an Oracle connection to generate an extraction project.</p>}
<p>Only the selected method is generated. BAU worksheets are not extraction inputs. Saved passwords are never exported.</p></section>
<section class="panel"><h2>Project settings</h2><div class="two-column">
<label>Extraction method<select [(ngModel)]="engine"><option value="JDBC">JDBC Cursor</option><option value="SQLCL">SQLcl</option></select></label>
<label>Parallel extraction jobs<input type="number" min="1" [(ngModel)]="parallelJobs"></label>
<label>Delimiter<select [(ngModel)]="delimiterChoice"><option value="BEL">BEL (default)</option><option value="PIPE">Pipe |</option><option value="TAB">Tab</option><option value="COMMA">Comma</option><option value="SPACE">Space</option><option value="CUSTOM">Custom</option></select></label>
@if(delimiterChoice==='CUSTOM'){<label>Custom delimiter<input [(ngModel)]="customDelimiter"></label>}
<label>Output directory<input [(ngModel)]="outputDirectory"></label><label>Delivery directory (optional)<input [(ngModel)]="deliveryDirectory"></label>
<label>File name pattern<input [(ngModel)]="fileNamePattern"></label><label>System code<input [(ngModel)]="systemCode"></label><label>Country code<input [(ngModel)]="countryCode"></label>
@if(engine==='SQLCL'){<label>Installed SQLcl executable path<input [(ngModel)]="sqlclExecutable"></label>}
</div>
@if(engine==='JDBC'){<p>Forward-only cursor, fetch size 2000. Write rows progressively and flush the file buffer after every 2000 rows.</p>}
@else{<p>SQLcl is installed separately. Spring Boot controls its process, disk-backed CSV staging, conversion, logs and exit status.</p>}
<p>Header, Detail and Trailer records are always included. NULL is \\N; values containing the delimiter or quotes are quoted. Text line breaks become spaces. Trailer: row count and optional numeric sum.</p>
<p>Filename tokens: {{'{schema}, {table}, {businessDate}'}}. The scheduler supplies business date, group and request ID when launching the built jar.</p>
<p>Maven coordinates: com.edac:extraction. Parent: com.uob.ufw:uob-ms-starter:3.6.15.2.RELEASE. JDK 17 or 21.</p></section>
<section class="panel"><h2>Tables from the table list</h2><label><input type="checkbox" [checked]="allSelected()" (change)="selectAll($any($event.target).checked)">Select all tables ({{selected().size}} / {{api.tables().length}})</label>
<div class="table-scroll"><table><thead><tr><th>Select</th><th>Schema</th><th>Table</th><th>Load strategy</th><th>Update Freq / Scheduler group</th></tr></thead><tbody>@for(t of api.tables();track t.id){<tr><td><input type="checkbox" [attr.aria-label]="'Extract '+t.schema+'.'+t.name" [checked]="selected().has(t.id)" (change)="toggle(t.id,$any($event.target).checked)"></td><td>{{t.schema}}</td><td>{{t.name}}</td><td>{{t.loadStrategy}}</td><td>{{t.updateFrequency}}</td></tr>}</tbody></table></div>
<p>Mapping workbook column exclusions are respected. The trailer control sum automatically uses the first included Type N field, matching Hash Sum 1. No numeric field means no control sum.</p></section>
<section class="panel"><h2>Table rules</h2><label>Table options<select [ngModel]="optionTable" (ngModelChange)="loadColumns($event)"><option [ngValue]="null">Choose a table</option>@for(t of deltaTables();track t.id){<option [ngValue]="t.id">{{t.schema}}.{{t.name}}</option>}</select></label>
@if(optionTable!==null){
@if(columnsLoading()){<p role="status">Loading columns from Oracle?</p>}
@if(columnsError()){<p role="alert">{{columnsError()}}</p><button type="button" (click)="loadColumns(optionTable)" [disabled]="columnsLoading()">Retry columns</button>}
@if(!columnsLoading()&&!columnsError()&&!columns().length){<p>No included columns were returned by Oracle. Check the table metadata and mapping exclusions.</p>}
@let option=options[''+optionTable];<div class="two-column">
<p>Scheduler group: {{tableGroup()}} (from Update Freq).</p>
<p>Control sum column: {{automaticSum()}} (automatic).</p>
<label>Extraction rule<select [(ngModel)]="option.delta.mode"><option value="RANGE">Delta range</option><option value="FLAG">Delta flag</option></select></label>
@if(option.delta.mode!=='FULL'){
<label>Delta column<select [ngModel]="option.delta.column" (ngModelChange)="chooseColumn($event)"><option value="">Choose a column</option>@for(c of columns();track c.name){@if(c.deltaType && (option.delta.mode==='FLAG'||c.deltaType!=='TEXT')){<option [value]="c.name">{{c.name}} ({{c.dataType}})</option>}}</select></label>
@if(option.delta.mode==='FLAG'){<label>Flag value<input [(ngModel)]="option.delta.flagValue"></label>}
@else{
<label>Lower bound source<select [(ngModel)]="option.delta.lowerSource">@if(!numericDelta()){<option value="BUSINESS_DATE">Business date start</option>}<option value="CHECKPOINT">Last successful checkpoint</option><option value="PARAMETER">Run parameter</option></select></label>
<label>Upper bound source<select [(ngModel)]="option.delta.upperSource">@if(!numericDelta()){<option value="BUSINESS_DATE_END">Next business date start</option>}<option value="PARAMETER">Run parameter</option></select></label>
<label>Lower operator<select [(ngModel)]="option.delta.lowerOperator"><option value=">=">Greater than or equal</option><option value=">">Greater than</option></select></label>
<label>Upper operator<select [(ngModel)]="option.delta.upperOperator"><option value="<">Less than</option><option value="<=">Less than or equal</option></select></label>
<label>Initial lower bound (optional)<input [(ngModel)]="option.delta.initialValue"></label>
<p>For business date 20261105 (or 2026-11-05), Business date start is 2026-11-05 00:00:00 and Next business date start is 2026-11-06 00:00:00. The scheduler supplies the date; it is not the server clock. DATE values use YYYY-MM-DD HH:mm:ss; TIMESTAMP values include fractional seconds. JDBC binds typed timestamps; SQLcl converts explicitly. Current binding/session timezone: UTC.</p>
<p>Run parameters: {{'--extraction.parameters.t'+optionTable+'-from=...'}} and {{'--extraction.parameters.t'+optionTable+'-to=...'}}. Numeric ranges require numeric bounds. Checkpoints advance after publication and delivery.</p>
}}</div>}
<p>Only selected tables with Load Strategy = Delta appear here. Full tables need no rules; their strategy and all scheduler groups come from the Table List. Tables do not share a snapshot.</p></section>
<section class="panel"><button class="button" (click)="save()" [disabled]="api.busy()||!configReady()||!selected().size||api.selected()?.databaseType!=='ORACLE'">Save configuration</button><p>Saved project settings and delta rules reload when you return. Successful ZIP generation also saves the configuration. Rules for deselected tables are retained.</p><button class="button" (click)="generate()" [disabled]="api.busy()||!configReady()||!selected().size||api.selected()?.databaseType!=='ORACLE'">Generate source project ZIP</button><p role="status">{{message()}}</p></section>
`})
export class ExtractionComponent {
 api=inject(Api);selected=signal(new Set<number>());columns=signal<Column[]>([]);columnsLoading=signal(false);columnsError=signal('');message=signal('');optionTable:number|null=null;
 engine='JDBC';delimiterChoice='BEL';customDelimiter='||';parallelJobs=1;systemCode='PBI';countryCode='SG';outputDirectory='./output';deliveryDirectory='';fileNamePattern='{schema}_{table}_{businessDate}.dat';sqlclExecutable='/opt/sqlcl/bin/sql';
 options:Record<string,TableOption>={};private current:number|null=null;private version=0;private known=new Set<number>();private stored:Settings|null=null;private configVersion=0;configReady=signal(false);
 allSelected=computed(()=>this.api.tables().length>0&&this.selected().size===this.api.tables().length);
 chosenTables=computed(()=>this.api.tables().filter(t=>this.selected().has(t.id)));
 deltaTables=computed(()=>this.chosenTables().filter(t=>t.loadStrategy?.toUpperCase()==='DELTA'));
 automaticSum(){return this.columnsLoading()?'Loading':this.columnsError()?'Unavailable until columns load':this.columns().find(c=>c.numeric)?.name??'None';}
 tableGroup(){return this.api.tables().find(t=>t.id===this.optionTable)?.updateFrequency??'';}
 numericDelta(){return this.optionTable!==null&&this.columns().find(c=>c.name===this.options[''+this.optionTable]?.delta.column)?.deltaType==='NUMBER';}
 constructor(){effect(()=>{const connection=this.api.selectedId(),tables=this.api.tables();if(this.current!==connection){this.current=connection;this.known=new Set();this.options={};this.optionTable=null;this.columns.set([]);this.message.set('');this.columnsError.set('');this.columnsLoading.set(false);this.version++;this.configVersion++;this.stored=null;this.resetSettings();this.configReady.set(false);if(connection!==null&&this.api.selected()?.databaseType==='ORACLE')void this.restore(connection,this.configVersion);}
 const available=new Set(tables.map(t=>t.id)),selection=new Set([...this.selected()].filter(id=>available.has(id)));for(const t of tables){if(!this.known.has(t.id)&&(!this.stored||this.stored.tableIds.includes(t.id)||!Object.hasOwn(this.stored.tableOptions,String(t.id))))selection.add(t.id);this.options[''+t.id]??={group:t.updateFrequency||'Daily',controlSumColumn:'',delta:{mode:t.loadStrategy?.toUpperCase()==='DELTA'?'RANGE':'FULL',column:'',lowerSource:'BUSINESS_DATE',upperSource:'BUSINESS_DATE_END',initialValue:'',flagValue:'',lowerOperator:'>=',upperOperator:'<'}};if(t.loadStrategy?.toUpperCase()==='DELTA'&&this.options[''+t.id].delta.mode==='FULL')this.options[''+t.id].delta.mode='RANGE';}this.known=available;this.clearOptions();
 if(selection.size!==this.selected().size||[...selection].some(id=>!this.selected().has(id)))this.selected.set(selection);
 });}
 changeConnection(id:number){void this.api.run(()=>this.api.select(id));}
 selectAll(value:boolean){this.selected.set(new Set(value?this.api.tables().map(t=>t.id):[]));this.clearOptions();}
 toggle(id:number,value:boolean){const next=new Set(this.selected());value?next.add(id):next.delete(id);this.selected.set(next);this.clearOptions();}
 clearOptions(){if(this.optionTable!==null&&!this.deltaTables().some(t=>t.id===this.optionTable)){this.optionTable=null;this.columns.set([]);this.columnsError.set('');this.columnsLoading.set(false);this.version++;}}
 async loadColumns(id:number|null){
  this.optionTable=id;this.columns.set([]);this.columnsError.set('');this.columnsLoading.set(false);const version=++this.version,connection=this.api.selectedId();if(id===null||connection===null)return;
  this.columnsLoading.set(true);
  try{const columns=await this.api.get<Column[]>('/connections/'+connection+'/extraction-project/columns/'+id);if(version===this.version&&connection===this.api.selectedId())this.columns.set(columns);}
  catch(error){if(version===this.version&&connection===this.api.selectedId()){const problem=error as {error?:{message?:string};message?:string};this.columnsError.set('Cannot load columns from Oracle. '+(problem.error?.message??problem.message??'Check the source database connection.')+' Retry when the source is available.');}}
  finally{if(version===this.version&&connection===this.api.selectedId())this.columnsLoading.set(false);}
 }
 chooseColumn(name:string){if(this.optionTable===null)return;const delta=this.options[''+this.optionTable].delta;delta.column=name;if(this.columns().find(c=>c.name===name)?.deltaType==='NUMBER'){delta.lowerSource='PARAMETER';delta.upperSource='PARAMETER';delta.lowerOperator='>';delta.upperOperator='<=';}}
 delimiter(){return ({BEL:'\u0007',SPACE:' ',TAB:'\t',PIPE:'|',COMMA:','} as Record<string,string>)[this.delimiterChoice]??this.customDelimiter;}
 private resetSettings(){this.engine='JDBC';this.delimiterChoice='BEL';this.customDelimiter='||';this.parallelJobs=1;this.systemCode='PBI';this.countryCode='SG';this.outputDirectory='./output';this.deliveryDirectory='';this.fileNamePattern='{schema}_{table}_{businessDate}.dat';this.sqlclExecutable='/opt/sqlcl/bin/sql';}
 private async restore(connection:number,version:number){
  try{const result=await this.api.get<{settings:Settings|null}>('/connections/'+connection+'/extraction-project/configuration');if(connection!==this.api.selectedId()||version!==this.configVersion)return;
   const value=result.settings;this.stored=value;if(value){this.engine=value.engine;this.parallelJobs=value.parallelJobs;this.systemCode=value.systemCode;this.countryCode=value.countryCode;this.outputDirectory=value.outputDirectory;this.deliveryDirectory=value.deliveryDirectory??'';this.fileNamePattern=value.fileNamePattern;this.sqlclExecutable=value.sqlclExecutable||'/opt/sqlcl/bin/sql';
    const choices:Record<string,string>={'\u0007':'BEL',' ':'SPACE','\t':'TAB','|':'PIPE',',':'COMMA'};this.delimiterChoice=choices[value.delimiter]??'CUSTOM';if(this.delimiterChoice==='CUSTOM')this.customDelimiter=value.delimiter;
    for(const [id,option] of Object.entries(value.tableOptions))if(option.delta)this.options[id]={group:'',controlSumColumn:'',delta:option.delta};
    this.selected.set(new Set(this.api.tables().filter(t=>value.tableIds.includes(t.id)||!Object.hasOwn(value.tableOptions,String(t.id))).map(t=>t.id)));
   }this.configReady.set(true);
  }catch(error){if(connection===this.api.selectedId()&&version===this.configVersion)this.api.error.set('Cannot load saved extraction configuration. Refresh to retry; '+(error instanceof Error?error.message:'check the backend connection.'));}
 }
 private settings():Settings {const ids=[...this.selected()],tableOptions=Object.fromEntries(this.deltaTables().map(t=>[''+t.id,{delta:this.options[''+t.id].delta}]));return {tableIds:ids,engine:this.engine,delimiter:this.delimiter(),parallelJobs:this.parallelJobs,systemCode:this.systemCode,countryCode:this.countryCode,outputDirectory:this.outputDirectory,deliveryDirectory:this.deliveryDirectory,fileNamePattern:this.fileNamePattern,sqlclExecutable:this.engine==='SQLCL'?this.sqlclExecutable:null,tableOptions};}
 save(){const connection=this.api.selectedId();if(connection===null)return;void this.api.run(async()=>{this.message.set('');await this.api.post('/connections/'+connection+'/extraction-project/configuration',this.settings());if(connection===this.api.selectedId())this.message.set('Configuration saved. Project settings and delta rules will be restored next time.');});}
 generate(){const connection=this.api.selectedId();if(connection===null)return;void this.api.run(async()=>{this.message.set('');const settings=this.settings();
 const blob=await this.api.downloadExtractionProject(connection,settings);
 const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='EDAC_Extraction_'+settings.engine+'_Project.zip';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);this.message.set('Source project generated for '+settings.tableIds.length+' table(s). Configuration saved for regeneration.');});}
}
